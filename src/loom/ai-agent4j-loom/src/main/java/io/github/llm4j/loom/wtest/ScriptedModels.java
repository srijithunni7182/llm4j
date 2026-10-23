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

    ScriptedModels(
            Map<String, JsonNode> scripts, boolean lenient, boolean repeatLast, Recorder recorder) {
        this.scripts = scripts;
        this.lenient = lenient;
        this.repeatLast = repeatLast;
        this.recorder = recorder;
    }

    @Override
    public LLMClient createClient(String model) {
        String agent = model.startsWith(PREFIX) ? model.substring(PREFIX.length()) : model;
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                int n = recorder.call(agent);
                String task = lastUser(request);
                String answer = answer(agent, n, task);
                String reply =
                        "```json\n{\"thought\": \"scripted\", \"final_answer\": "
                                + quote(answer)
                                + "}\n```";
                int in = tokens(request);
                int out = (int) CharsPerTokenEstimator.INSTANCE.completion(reply);
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

    private String answer(String agent, int call, String task) {
        JsonNode script = scripts.get(agent);
        if (script == null) return unscripted(agent, call, task, "has no scripted answer");
        if (script.isTextual()) return script.asText();
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
            return text(script.get(i));
        }
        if (script.isObject() && script.has("when")) {
            for (JsonNode rule : script.get("when")) {
                if (rule.has("otherwise")) return text(rule.get("otherwise"));
                String reply = text(rule.has("reply") ? rule.get("reply") : rule);
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
        if (script.isObject()) return script.toString(); // a typed answer
        return text(script);
    }

    private String unscripted(String agent, int call, String task, String why) {
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
        return MockModels.ANSWER;
    }

    private static String text(JsonNode n) {
        return n.isTextual() ? n.asText() : n.toString();
    }

    private static String lastUser(LLMRequest request) {
        List<Message> messages = new ArrayList<>(request.getMessages());
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.getRole() == Message.Role.USER && m.getContent() != null) return m.getContent();
        }
        return "";
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
