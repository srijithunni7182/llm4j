package io.github.llm4j.loom.debug.dap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.debug.Display;
import io.github.llm4j.loom.debug.Stepper;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.VariableView;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.ObserveReplay;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Debug Adapter Protocol over a pair of streams, mapped onto a {@link Stepper}. There is no
 * debugging logic here: each request is a call on the stepper, and the answer is what the stepper
 * says. A person pressing F5 on a workflow gets the same run that {@code weave step} gives.
 *
 * <p>Effects are simulated and models are mock unless the launch asks for live models, which needs
 * a cap and {@code confirmed: true} (the editor asks the person first). One thread stands for the
 * workflow: branches of a {@code parallel} block are not announced one by one by the interpreter.
 */
public final class DapServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int THREAD = 1;
    private static final Pattern LINE_OF = Pattern.compile("line (\\d+)\\s+(.*)", Pattern.DOTALL);

    private final InputStream in;
    private final OutputStream out;
    private final LLMClientFactory models;
    private final Function<String, String> env;

    private int seq = 1;
    private Stepper stepper;
    private Path script;
    private boolean stopOnEntry = true;
    private boolean started;
    private final Map<String, List<JsonNode>> lineBreakpoints = new LinkedHashMap<>();
    private List<JsonNode> functionBreakpoints = List.of();

    public DapServer(
            InputStream in,
            OutputStream out,
            LLMClientFactory models,
            Function<String, String> env) {
        this.in = in;
        this.out = out;
        this.models = models;
        this.env = env;
    }

    /** Serves requests until {@code disconnect} or the end of the input. */
    public void serve() throws IOException {
        try {
            while (true) {
                JsonNode message = read();
                if (message == null) return;
                if (!"request".equals(message.path("type").asText())) continue;
                if (!handle(message)) return;
            }
        } finally {
            if (stepper != null) stepper.close();
        }
    }

    // ---- wire

    private JsonNode read() throws IOException {
        int length = -1;
        while (true) {
            String line = headerLine();
            if (line == null) return null;
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length"))
                try {
                    length = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    length = -1;
                }
        }
        if (length < 0 || length > 16 * 1024 * 1024) {
            output("console", "A message without a usable Content-Length was ignored.");
            return read();
        }
        byte[] body = in.readNBytes(length);
        if (body.length < length) return null;
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            output("console", "A message that is not JSON was ignored.");
            return read();
        }
    }

    private String headerLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
            if (b == '\n') {
                String s = line.toString(StandardCharsets.UTF_8);
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            line.write(b);
        }
    }

    private synchronized void send(ObjectNode message) {
        message.put("seq", seq++);
        try {
            byte[] body = JSON.writeValueAsBytes(message);
            out.write(
                    ("Content-Length: " + body.length + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void event(String name, ObjectNode body) {
        ObjectNode m = JSON.createObjectNode();
        m.put("type", "event").put("event", name);
        if (body != null) m.set("body", body);
        send(m);
    }

    private void output(String category, String text) {
        ObjectNode b = JSON.createObjectNode();
        b.put("category", category).put("output", Display.safe(text) + "\n");
        event("output", b);
    }

    private void respond(JsonNode request, boolean ok, String message, ObjectNode body) {
        ObjectNode m = JSON.createObjectNode();
        m.put("type", "response").put("request_seq", request.path("seq").asInt());
        m.put("success", ok).put("command", request.path("command").asText());
        if (message != null) m.put("message", message);
        if (body != null) m.set("body", body);
        send(m);
    }

    // ---- requests

    private boolean handle(JsonNode request) {
        String command = request.path("command").asText();
        JsonNode args = request.path("arguments");
        try {
            switch (command) {
                case "initialize" -> initialize(request);
                case "launch" -> launch(request, args);
                case "configurationDone" -> configurationDone(request);
                case "setBreakpoints" -> setBreakpoints(request, args);
                case "setFunctionBreakpoints" -> setFunctionBreakpoints(request, args);
                case "setExceptionBreakpoints" -> respond(
                        request, true, null, JSON.createObjectNode());
                case "threads" -> threads(request);
                case "stackTrace" -> stackTrace(request);
                case "scopes" -> scopes(request);
                case "variables" -> variables(request);
                case "continue" -> move(request, Stepper.Move.CONTINUE);
                case "next" -> move(request, Stepper.Move.OVER);
                case "stepIn" -> move(request, Stepper.Move.INTO);
                case "stepOut" -> move(request, Stepper.Move.OUT);
                case "stepBack", "loom/stepBack" -> stepBack(request);
                case "reverseContinue" -> reverseContinue(request);
                case "setVariable" -> setVariable(request, args);
                case "evaluate" -> evaluate(request, args);
                case "loom/why" -> why(request);
                case "terminate", "disconnect" -> {
                    respond(request, true, null, null);
                    if (command.equals("terminate")) {
                        event("terminated", null);
                        return true;
                    }
                    return false;
                }
                default -> respond(
                        request, false, "The request " + command + " is not supported.", null);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            respond(
                    request,
                    false,
                    Display.safe(e.getMessage() == null ? e.toString() : e.getMessage()),
                    null);
        } catch (RuntimeException e) {
            respond(
                    request,
                    false,
                    "The debugger failed: " + Display.safe(String.valueOf(e.getMessage())),
                    null);
        }
        return true;
    }

    private void initialize(JsonNode request) {
        ObjectNode caps = JSON.createObjectNode();
        caps.put("supportsConfigurationDoneRequest", true);
        caps.put("supportsStepBack", true);
        caps.put("supportsConditionalBreakpoints", true);
        caps.put("supportsSetVariable", true);
        caps.put("supportsEvaluateForHovers", true);
        caps.put("supportsFunctionBreakpoints", true);
        caps.put("supportsTerminateRequest", true);
        caps.set("exceptionBreakpointFilters", JSON.createArrayNode());
        respond(request, true, null, caps);
    }

    private void launch(JsonNode request, JsonNode args) {
        if (stepper != null)
            throw new IllegalStateException("The debugger is already running a workflow.");
        String file = args.path("script").asText("");
        if (file.isBlank())
            throw new IllegalArgumentException("The launch configuration needs a \"script\".");
        script = Path.of(file).toAbsolutePath().normalize();
        if (!Files.isRegularFile(script))
            throw new IllegalArgumentException("The script " + script + " is not there.");
        String effects = args.path("effects").asText("simulate");
        if (!effects.equals("simulate"))
            throw new IllegalArgumentException(
                    "Only simulated effects can be debugged: nothing is sent or written. Use \"effects\": \"simulate\".");
        String modelsMode = args.path("models").asText("mock");
        ObserveReplay.Live live = null;
        if (modelsMode.equals("live")) {
            Long maxTokens = args.hasNonNull("maxTokens") ? args.get("maxTokens").asLong() : null;
            java.math.BigDecimal maxCost =
                    args.hasNonNull("maxCost")
                            ? new java.math.BigDecimal(args.get("maxCost").asText())
                            : null;
            if (maxTokens == null && maxCost == null)
                throw new IllegalArgumentException(
                        "Live models spend money: give \"maxCost\" (with \"prices\") or \"maxTokens\" in the launch configuration.");
            if (maxCost != null && !args.hasNonNull("prices"))
                throw new IllegalArgumentException(
                        "\"maxCost\" needs \"prices\" to turn tokens into money.");
            if (!args.path("confirmed").asBoolean(false))
                throw new IllegalArgumentException(
                        "Live models may spend up to "
                                + (maxCost != null ? maxCost + " " : "")
                                + (maxTokens != null ? maxTokens + " tokens " : "")
                                + "calling real models. The editor asks first and sets \"confirmed\": true.");
            io.github.llm4j.budget.PriceTable prices = null;
            if (args.hasNonNull("prices"))
                try {
                    prices =
                            io.github.llm4j.budget.PriceTable.load(
                                    Path.of(args.get("prices").asText()));
                } catch (Exception e) {
                    throw new IllegalArgumentException("Cannot read the prices: " + e.getMessage());
                }
            live = new ObserveReplay.Live(models, maxTokens, maxCost, prices, env);
        } else if (!List.of("mock", "scripted").contains(modelsMode)) {
            throw new IllegalArgumentException("\"models\" takes mock, scripted or live.");
        }
        Map<String, String> inputs = new LinkedHashMap<>();
        args.path("input")
                .fields()
                .forEachRemaining(e -> inputs.put(e.getKey(), e.getValue().asText()));
        String workflow = args.path("workflow").asText("");
        if (workflow.isBlank()) {
            try {
                var loaded =
                        new io.github.llm4j.loom.execution.LoomLoader().load(script.toString());
                if (loaded.getWorkflows().isEmpty())
                    throw new IllegalArgumentException(script + " has no workflow.");
                workflow = loaded.getWorkflows().get(0).getName();
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }
        stopOnEntry = args.path("stopOnEntry").asBoolean(true);
        java.util.Set<String> masked = new LinkedHashSet<>();
        args.path("mask").forEach(n -> masked.add(n.asText()));
        stepper =
                Stepper.open(
                        new Stepper.Config(
                                script, workflow, inputs, masked, RunJournal.inMemory(), live));
        respond(request, true, null, null);
        event("initialized", null);
    }

    private void configurationDone(JsonNode request) {
        respond(request, true, null, null);
        started = true;
        if (stopOnEntry || stepper.state().finished()) report(stepper.state(), "entry");
        else report(stepper.move(Stepper.Move.CONTINUE), null);
    }

    /** Tells the editor where the run is now. */
    private void report(Stepper.State s, String reason) {
        if (s.finished()) {
            output(
                    "console",
                    "The run "
                            + s.status()
                            + (s.reason().isBlank() ? "" : ": " + s.reason())
                            + (s.result() == null ? "" : ". Result: " + s.result()));
            if ("suspended".equals(s.status()))
                output(
                        "console",
                        "It waits for a person's answer; the debugger cannot give one yet. Use weave step to answer.");
            event("terminated", null);
            return;
        }
        boolean breakpoint = s.hit().startsWith("breakpoint");
        ObjectNode b = JSON.createObjectNode();
        b.put("reason", reason != null ? reason : breakpoint ? "breakpoint" : "step");
        b.put("threadId", THREAD).put("allThreadsStopped", true);
        b.put("description", Display.safe(s.position().statement()));
        event("stopped", b);
        if (breakpoint || "entry".equals(reason))
            output("console", s.calls() + " model calls, " + s.tokens() + " tokens so far.");
    }

    private void setBreakpoints(JsonNode request, JsonNode args) {
        String source = args.path("source").path("path").asText("");
        List<JsonNode> wanted = new ArrayList<>();
        JsonNode list = args.has("breakpoints") ? args.get("breakpoints") : args.path("lines");
        list.forEach(
                n -> wanted.add(n.isNumber() ? JSON.createObjectNode().put("line", n.asInt()) : n));
        lineBreakpoints.put(source, wanted);
        ArrayNode results = apply(source);
        ObjectNode body = JSON.createObjectNode();
        body.set("breakpoints", results);
        respond(request, true, null, body);
    }

    private void setFunctionBreakpoints(JsonNode request, JsonNode args) {
        List<JsonNode> wanted = new ArrayList<>();
        args.path("breakpoints").forEach(wanted::add);
        functionBreakpoints = wanted;
        ArrayNode results = apply(null);
        ObjectNode body = JSON.createObjectNode();
        body.set("breakpoints", results);
        respond(request, true, null, body);
    }

    /**
     * Rebuilds the stepper's breakpoints from everything the editor has set; answers for one set.
     */
    private ArrayNode apply(String answerFor) {
        ArrayNode answer = JSON.createArrayNode();
        if (stepper == null) {
            // before the launch the breakpoints are only remembered
            List<JsonNode> mine =
                    answerFor == null
                            ? functionBreakpoints
                            : lineBreakpoints.getOrDefault(answerFor, List.of());
            mine.forEach(
                    n ->
                            answer.add(
                                    JSON.createObjectNode()
                                            .put("verified", false)
                                            .put("message", "The workflow has not started yet.")));
            return answer;
        }
        stepper.breakpoints().clear();
        for (var entry : lineBreakpoints.entrySet()) {
            boolean forScript = samePath(entry.getKey(), script);
            for (JsonNode n : entry.getValue()) {
                ObjectNode r = JSON.createObjectNode();
                if (!forScript) {
                    r.put("verified", false)
                            .put("message", "That file is not the script being debugged.");
                } else {
                    try {
                        String spec = n.path("line").asInt() + condition(n);
                        var b = stepper.breakAt(spec);
                        r.put("verified", true).put("line", b.line());
                    } catch (IllegalArgumentException e) {
                        r.put("verified", false).put("message", Display.safe(e.getMessage()));
                    }
                }
                if (entry.getKey().equals(answerFor)) answer.add(r);
            }
        }
        for (JsonNode n : functionBreakpoints) {
            ObjectNode r = JSON.createObjectNode();
            try {
                stepper.breakAt(n.path("name").asText() + condition(n));
                r.put("verified", true);
            } catch (IllegalArgumentException e) {
                r.put("verified", false).put("message", Display.safe(e.getMessage()));
            }
            if (answerFor == null) answer.add(r);
        }
        return answer;
    }

    private static String condition(JsonNode n) {
        String c = n.path("condition").asText("").strip();
        return c.isEmpty() ? "" : " when " + c;
    }

    private static boolean samePath(String a, Path b) {
        try {
            return Path.of(a).toAbsolutePath().normalize().equals(b);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void threads(JsonNode request) {
        ObjectNode body = JSON.createObjectNode();
        body.putArray("threads")
                .add(JSON.createObjectNode().put("id", THREAD).put("name", "workflow"));
        respond(request, true, null, body);
    }

    private void stackTrace(JsonNode request) {
        requireStarted();
        Stepper.State s = stepper.state();
        ArrayNode frames = JSON.createArrayNode();
        if (!s.finished()) {
            var p = s.position();
            frames.add(frame(1, p.statement(), p.line()));
            List<String> enclosing = p.enclosing();
            for (int i = enclosing.size() - 1, id = 2; i >= 0; i--, id++) {
                Matcher m = LINE_OF.matcher(enclosing.get(i));
                frames.add(
                        m.matches()
                                ? frame(id, m.group(2), Integer.parseInt(m.group(1)))
                                : frame(id, enclosing.get(i), 0));
            }
            frames.add(frame(frames.size() + 1, "workflow " + p.workflow(), 0));
        }
        ObjectNode body = JSON.createObjectNode();
        body.set("stackFrames", frames);
        body.put("totalFrames", frames.size());
        respond(request, true, null, body);
    }

    private ObjectNode frame(int id, String name, int line) {
        ObjectNode f = JSON.createObjectNode();
        f.put("id", id).put("name", Display.safe(name)).put("line", line).put("column", 1);
        f.set(
                "source",
                JSON.createObjectNode()
                        .put("name", script.getFileName().toString())
                        .put("path", script.toString()));
        return f;
    }

    private void scopes(JsonNode request) {
        ObjectNode body = JSON.createObjectNode();
        body.putArray("scopes")
                .add(
                        JSON.createObjectNode()
                                .put("name", "Variables")
                                .put("variablesReference", 1)
                                .put("expensive", false));
        respond(request, true, null, body);
    }

    private void variables(JsonNode request) {
        requireStarted();
        ArrayNode list = JSON.createArrayNode();
        if (!stepper.state().finished())
            for (VariableView v : stepper.state().variables()) {
                ObjectNode n = JSON.createObjectNode();
                n.put("name", v.name());
                n.put("value", Display.safe(v.value()) + provenance(v));
                n.put("type", v.type());
                n.put("variablesReference", 0);
                list.add(n);
            }
        ObjectNode body = JSON.createObjectNode();
        body.set("variables", list);
        respond(request, true, null, body);
    }

    private static String provenance(VariableView v) {
        if (v.boundBy() == null) return v.value().equals("‹masked›") ? "" : "   (input)";
        return "   ("
                + (v.boundLine() > 0 ? "line " + v.boundLine() + ": " : "")
                + Display.safe(v.boundBy())
                + ")";
    }

    private void move(JsonNode request, Stepper.Move move) {
        requireStarted();
        respond(
                request,
                true,
                null,
                move == Stepper.Move.CONTINUE
                        ? JSON.createObjectNode().put("allThreadsContinued", true)
                        : null);
        report(stepper.move(move), null);
    }

    private void stepBack(JsonNode request) {
        requireStarted();
        Stepper.State s = stepper.back();
        respond(request, true, null, null);
        report(s, null);
    }

    private void reverseContinue(JsonNode request) {
        requireStarted();
        Stepper.State s = stepper.back();
        while (!s.finished() && s.position().index() > 0 && !s.hit().startsWith("breakpoint"))
            s = stepper.back();
        respond(request, true, null, null);
        report(s, null);
    }

    private void setVariable(JsonNode request, JsonNode args) {
        requireStarted();
        Stepper.State s = stepper.set(args.path("name").asText(), args.path("value").asText());
        ObjectNode body = JSON.createObjectNode();
        String shown =
                s.variables().stream()
                        .filter(v -> v.name().equals(args.path("name").asText()))
                        .map(VariableView::value)
                        .findFirst()
                        .orElse(args.path("value").asText());
        body.put("value", Display.safe(shown));
        respond(request, true, null, body);
    }

    private static final Pattern COMPARISON = Pattern.compile(".*(==|!=|>=|<=|>|<|\\s+is\\s+).*");

    private void evaluate(JsonNode request, JsonNode args) {
        requireStarted();
        if (stepper.state().finished()) throw new IllegalStateException("The run has ended.");
        String expression = args.path("expression").asText("").strip();
        if (expression.startsWith("p ")) expression = expression.substring(2).strip();
        if (expression.isEmpty()) throw new IllegalArgumentException("Nothing to evaluate.");
        ObjectNode body = JSON.createObjectNode();
        String result;
        if (COMPARISON.matcher(expression).matches())
            result = String.valueOf(stepper.holds(expression));
        else {
            Object value = stepper.value(expression);
            result = value == null ? "no value" : String.valueOf(value);
        }
        if (result.length() > 200) result = result.substring(0, 200) + "…";
        body.put("result", Display.safe(result)).put("variablesReference", 0);
        respond(request, true, null, body);
    }

    private void why(JsonNode request) {
        requireStarted();
        ObjectNode body = JSON.createObjectNode();
        var s = stepper.state();
        body.put(
                "text",
                Display.safe(
                        s.finished()
                                ? "The run has ended."
                                : "Stopped at " + s.position().statement()));
        respond(request, true, null, body);
    }

    private void requireStarted() {
        if (stepper == null || !started)
            throw new IllegalStateException("The workflow has not been launched.");
    }
}
