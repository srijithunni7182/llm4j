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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    ScriptedModels(
            Map<String, JsonNode> scripts,
            boolean lenient,
            boolean repeatLast,
            Recorder recorder,
            Failures failures,
            JsonNode usage,
            Interventions interventions) {
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
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
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
                String reply = envelope(chosen);
                int in = tokens(request);
                int out = (int) CharsPerTokenEstimator.INSTANCE.completion(reply);
                JsonNode u = usage == null ? null : usage.get(agent);
                if (u != null) {
                    in = u.path("in").asInt(in);
                    out = u.path("out").asInt(out);
                }
                recorder.usage(in, out);
                interventions.afterCall(agent, n);
                return LLMResponse.builder()
                        .content(reply)
                        .model(model)
                        .tokenUsage(in, out, in + out)
                        .build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
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
