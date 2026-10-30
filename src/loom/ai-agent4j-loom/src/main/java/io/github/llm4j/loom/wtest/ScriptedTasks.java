package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskContext;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskResult;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@code run} task in a test case answers from the case ({@code tasks:}): a text or value, a map
 * of fields, an {@code outcome} with a {@code reason}, or an {@code error} with a category. A task
 * nobody scripted fails the case. Replaced tasks count as calls.
 */
final class ScriptedTasks {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ScriptedTasks() {}

    static Task of(
            String name,
            JsonNode script,
            Failures failures,
            boolean repeatLast,
            Recorder recorder) {
        return new Task() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public TaskEffect effect() {
                return TaskEffect.NONE;
            }

            @Override
            public TaskResult run(TaskContext context) throws Exception {
                int call = recorder.call("task:" + name);
                var fail = failures.failsOn(name, call);
                if (fail.isPresent()) throw (Exception) Failures.tool(fail.get(), name, call);
                if (script == null) {
                    String message = "task " + name + " has no scripted result (call " + call + ")";
                    recorder.violation(message);
                    throw new IllegalStateException(message);
                }
                JsonNode chosen = script;
                if (script.isArray()) {
                    int i = Math.min(call, script.size()) - 1;
                    if (call > script.size() && !repeatLast) {
                        String message =
                                "task "
                                        + name
                                        + " was called more often than its list of "
                                        + script.size()
                                        + " results (call "
                                        + call
                                        + ")";
                        recorder.violation(message);
                        throw new IllegalStateException(message);
                    }
                    chosen = script.get(i);
                }
                if (chosen.isObject() && chosen.has("error"))
                    throw (Exception) Failures.tool(chosen.get("error").asText(), name, call);
                if (chosen.isObject() && chosen.has("outcome")) {
                    TaskResult r = TaskResult.outcome(chosen.get("outcome").asText());
                    return chosen.has("reason") ? r.reason(chosen.get("reason").asText()) : r;
                }
                if (chosen.isObject()) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    chosen.fields()
                            .forEachRemaining(e -> data.put(e.getKey(), plain(e.getValue())));
                    try {
                        return TaskResult.ok(data);
                    } catch (IllegalArgumentException e) {
                        // a reserved key: say what to write instead (the registry of known mistakes)
                        String message =
                                io.github.llm4j.loom.hints.KnownMistakes.withHint(
                                        "task " + name + ": " + e.getMessage(), java.util.List.of(), -1);
                        recorder.violation(message);
                        throw new IllegalStateException(message, e);
                    }
                }
                return TaskResult.value(plain(chosen));
            }
        };
    }

    private static Object plain(JsonNode n) {
        if (n.isTextual()) return n.asText();
        if (n.isNumber()) return n.numberValue();
        if (n.isBoolean()) return n.asBoolean();
        try {
            return JSON.treeToValue(n, Object.class);
        } catch (Exception e) {
            return n.toString();
        }
    }
}
