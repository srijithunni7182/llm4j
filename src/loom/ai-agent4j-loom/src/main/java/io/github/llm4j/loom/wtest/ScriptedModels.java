package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.budget.CharsPerTokenEstimator;
import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCallDelta;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The models of a test case: each agent answers from a script, so a workflow runs with no model, no
 * key and no network. An agent's script is a text (every call answers it), a list (the calls answer
 * in order), a {@code when} list (the answer is chosen by the task text), or a map (a typed answer
 * for an agent with an {@code output_schema}).
 *
 * <p>The factory is told only a model name, so each agent of the script is given a model name of
 * its own, {@code scripted:<Agent>}, before the executor is built. A call that nothing scripted
 * fails the case and names the agent, the call number and the task, because a test must not pass by
 * silently getting a default reply; {@code lenient} allows a neutral reply and reports it.
 */
final class ScriptedModels implements LLMClientFactory {

    static final String PREFIX = "scripted:";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, JsonNode> scripts;
    private final boolean lenient;
    private final boolean repeatLast;
    private final Recorder recorder;
    private final Failures failures;
    private final JsonNode usage;
    private final Interventions interventions;
    private final Set<String> streaming;

    ScriptedModels(
            Map<String, JsonNode> scripts,
            boolean lenient,
            boolean repeatLast,
            Recorder recorder,
            Failures failures,
            JsonNode usage,
            Interventions interventions,
            Set<String> streaming) {
        this.streaming = streaming;
        this.scripts = scripts;
        this.lenient = lenient;
        this.repeatLast = repeatLast;
        this.recorder = recorder;
        this.failures = failures;
        this.usage = usage;
        this.interventions = interventions;
    }

    @Override
    public LLMClient createClient(String model) {
        String agent = model.startsWith(PREFIX) ? model.substring(PREFIX.length()) : model;
        // a streaming agent speaks the model's own tool protocol, because that is the loop that
        // streams; the others use the text protocol
        boolean natively = streaming.contains(agent);
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                return respond(request, true);
            }

            /**
             * One whole answer; {@code hook} says whether a steer or stop due after it happens now.
             */
            private LLMResponse respond(LLMRequest request, boolean hook) {
                int n = recorder.call(agent);
                String task = userText(request);
                var fail = failures.failsOn(agent, n);
                if (fail.isPresent()) {
                    if (fail.get().equals("invalid_output"))
                        return LLMResponse.builder()
                                .content("{ this is not a valid answer")
                                .model(model)
                                .tokenUsage(10, 5, 15)
                                .build();
                    throw Failures.model(fail.get(), agent, n);
                }
                JsonNode chosen = choose(agent, n, task);
                String reply = natively ? plain(chosen) : envelope(chosen);
                int in = tokens(request);
                int out = (int) CharsPerTokenEstimator.INSTANCE.completion(reply);
                JsonNode u = usage == null ? null : usage.get(agent);
                if (u != null) {
                    in = u.path("in").asInt(in);
                    out = u.path("out").asInt(out);
                }
                recorder.usage(in, out);
                if (hook) interventions.afterCall(agent, n);
                LLMResponse.Builder answer =
                        LLMResponse.builder()
                                .content(reply)
                                .model(model)
                                .tokenUsage(in, out, in + out);
                if (natively && isCall(chosen))
                    answer.content("").toolCalls(List.of(toolCall(chosen, n)));
                return answer.build();
            }

            @Override
            public boolean supportsToolCalling() {
                return natively;
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                int n = recorder.calls(agent) + 1;
                JsonNode script = scripts.get(agent);
                JsonNode streamed = streamOf(script, n);
                if (streamed == null) {
                    // a steer or stop due after this call comes when the reply has been read,
                    // not while it is being read (that would cut the reply off)
                    LLMResponse whole = respond(request, false);
                    int done = recorder.calls(agent);
                    Runnable after = () -> interventions.afterCall(agent, done);
                    if (whole.getToolCalls().isEmpty()) return Stream.of(whole).onClose(after);
                    var tc = whole.getToolCalls().get(0);
                    return Stream.of(
                                    LLMResponse.builder()
                                            .model(model)
                                            .toolCallDelta(
                                                    new ToolCallDelta(
                                                            0, tc.id(), tc.name(), quoteArgs(tc)))
                                            .tokenUsage(whole.getTokenUsage())
                                            .build())
                            .onClose(after);
                }
                // the reply arrives in pieces; a steer or a stop between two pieces ends the read
                int calls = recorder.call(agent);
                var fail = failures.failsOn(agent, calls);
                if (fail.isPresent()) throw Failures.model(fail.get(), agent, calls);
                int in = tokens(request);
                int[] seen = {0};
                List<String> pieces = new ArrayList<>();
                for (JsonNode piece : streamed) pieces.add(text(piece));
                return pieces.stream()
                        .map(
                                piece -> {
                                    LLMResponse r =
                                            LLMResponse.builder()
                                                    .content(piece)
                                                    .model(model)
                                                    .tokenUsage(in, 1, in + 1)
                                                    .build();
                                    interventions.afterChunk(agent, ++seen[0]);
                                    return r;
                                });
            }
        };
    }

    private static boolean isCall(JsonNode chosen) {
        return chosen.isObject() && chosen.has("call");
    }

    private static io.github.llm4j.model.ToolCall toolCall(JsonNode chosen, int n) {
        JsonNode args = chosen.has("with") ? chosen.get("with") : JSON.createObjectNode();
        return new io.github.llm4j.model.ToolCall(
                "scripted-" + n,
                chosen.get("call").asText(),
                JSON.convertValue(
                        args,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {}));
    }

    private static String quoteArgs(io.github.llm4j.model.ToolCall tc) {
        try {
            return JSON.writeValueAsString(tc.arguments());
        } catch (Exception e) {
            return "{}";
        }
    }

    /** The answer as the model's own protocol has it: the text itself. */
    private static String plain(JsonNode chosen) {
        if (chosen.isObject() && chosen.has("stream") && chosen.get("stream").isArray()) {
            StringBuilder all = new StringBuilder();
            chosen.get("stream").forEach(piece -> all.append(text(piece)));
            return all.toString();
        }
        return isCall(chosen) ? "" : text(chosen);
    }

    /** The pieces of call {@code n} when the script gives a {@code stream:}, else null. */
    private static JsonNode streamOf(JsonNode script, int n) {
        if (script == null) return null;
        JsonNode pick = script;
        if (script.isArray()) {
            if (script.isEmpty()) return null;
            pick = script.get(Math.min(n, script.size()) - 1);
        }
        return pick.isObject() && pick.has("stream") && pick.get("stream").isArray()
                ? pick.get("stream")
                : null;
    }

    @Override
    public String problem(String model) {
        return null;
    }

    // ---- choosing the answer

    private JsonNode choose(String agent, int call, String task) {
        JsonNode script = scripts.get(agent);
        if (script == null) return unscripted(agent, call, task, "has no scripted answer");
        if (script.isTextual()) return script;
        if (script.isArray()) {
            if (script.isEmpty())
                return unscripted(agent, call, task, "has an empty list of answers");
            int i = call - 1;
            if (i >= script.size()) {
                if (!repeatLast)
                    return unscripted(
                            agent,
                            call,
                            task,
                            "was called more often than its list of "
                                    + script.size()
                                    + " answers (repeat: last on the case repeats the last)");
                i = script.size() - 1;
            }
            return script.get(i);
        }
        if (script.isObject() && script.has("when")) {
            for (JsonNode rule : script.get("when")) {
                if (rule.has("otherwise")) return rule.get("otherwise");
                JsonNode reply = rule.has("reply") ? rule.get("reply") : rule;
                if (rule.has("contains") && task.contains(rule.get("contains").asText()))
                    return reply;
                if (rule.has("matches")
                        && Pattern.compile(rule.get("matches").asText()).matcher(task).find())
                    return reply;
            }
            return unscripted(
                    agent,
                    call,
                    task,
                    "has a list of rules and none of them matches the task (add an otherwise)");
        }
        return script; // a typed answer, or a tool call
    }

    private JsonNode unscripted(String agent, int call, String task, String why) {
        String message =
                "agent "
                        + agent
                        + " "
                        + why
                        + " (call "
                        + call
                        + "); its task was: "
                        + Values.cut(task.replace('\n', ' '), 160, false);
        if (!lenient) {
            recorder.violation(message);
            throw new IllegalStateException(message);
        }
        recorder.lenient(message);
        return JSON.getNodeFactory().textNode(MockModels.ANSWER);
    }

    /** What the model says: a tool call, or the final answer (text, or a typed answer as JSON). */
    private static String envelope(JsonNode chosen) {
        if (chosen.isObject() && chosen.has("stream") && chosen.get("stream").isArray()) {
            StringBuilder all = new StringBuilder();
            chosen.get("stream").forEach(piece -> all.append(text(piece)));
            chosen = JSON.getNodeFactory().textNode(all.toString());
        }
        if (chosen.isObject() && chosen.has("call")) {
            JsonNode args = chosen.has("with") ? chosen.get("with") : JSON.createObjectNode();
            return "```json\n{\"thought\": \"scripted\", \"action\": "
                    + quote(chosen.get("call").asText())
                    + ", \"action_input\": "
                    + args
                    + "}\n```";
        }
        return "```json\n{\"thought\": \"scripted\", \"final_answer\": "
                + quote(text(chosen))
                + "}\n```";
    }

    private static String text(JsonNode n) {
        return n.isTextual() ? n.asText() : n.toString();
    }

    /**
     * What the agent has been told so far: every message from the user side, in order. A rule that
     * looks for words in the task finds them whether they came with the task or later (a person's
     * steer, a tool's result).
     */
    private static String userText(LLMRequest request) {
        StringBuilder b = new StringBuilder();
        List<Message> messages = new ArrayList<>(request.getMessages());
        for (Message m : messages)
            if (m.getRole() == Message.Role.USER && m.getContent() != null) {
                if (b.length() > 0) b.append('\n');
                b.append(m.getContent());
            }
        return b.toString();
    }

    private static int tokens(LLMRequest request) {
        StringBuilder b = new StringBuilder();
        for (Message m : request.getMessages())
            if (m.getContent() != null) b.append(m.getContent());
        return (int) CharsPerTokenEstimator.INSTANCE.completion(b.toString());
    }

    private static String quote(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (Exception e) {
            return "\"" + s.replace("\"", "\\\"") + "\"";
        }
    }
}
