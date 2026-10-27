package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.ast.ToolDef;
import java.util.Map;
import java.util.TreeMap;

/**
 * A tool in a test case is never real. It answers from the case ({@code tools:}), counts its calls,
 * can be made to fail, and, for the kinds that act on the world (webhook, email, http, file, shell,
 * sql), records what it would have done (the tool, the kind, the target and a digest of the
 * arguments) so the case can assert on the effects. A call nobody scripted fails the case, except a
 * call to a tool that acts, which answers "ok" and is recorded.
 */
final class ScriptedTools {

    /** The tool kinds that act on the world; their calls are recorded as effects. */
    static final java.util.Set<String> EFFECT_KINDS =
            java.util.Set.of("webhook", "email", "http", "file", "shell", "sql");

    private static final ObjectMapper JSON = new ObjectMapper();

    private ScriptedTools() {}

    static Tool of(
            String name,
            ToolDef declared,
            JsonNode script,
            Failures failures,
            boolean repeatLast,
            Recorder recorder) {
        String kind = declared == null || declared.getKind() == null ? "" : declared.getKind();
        boolean acts = EFFECT_KINDS.contains(kind);
        String target = declared == null ? "" : target(declared);
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "Scripted tool " + name + (declared == null ? "" : " (" + kind + ")");
            }

            @Override
            public String execute(Map<String, Object> args) throws Exception {
                int call = recorder.call("tool:" + name);
                recorder.toolCalled(name);
                var fail = failures.failsOn(name, call);
                if (fail.isPresent()) throw Failures.tool(fail.get(), name, call);
                if (acts) recorder.effect(new Effect(name, kind, target, digest(args)));
                return result(call, args);
            }

            private String result(int call, Map<String, Object> args) {
                if (script == null) {
                    if (acts) return "ok";
                    return unscripted(call, "has no scripted result");
                }
                if (script.isTextual()) return script.asText();
                if (script.isArray()) {
                    int i = call - 1;
                    if (script.isEmpty()) return unscripted(call, "has an empty list of results");
                    if (i >= script.size()) {
                        if (!repeatLast)
                            return unscripted(
                                    call,
                                    "was called more often than its list of "
                                            + script.size()
                                            + " results (repeat: last on the case repeats the last)");
                        i = script.size() - 1;
                    }
                    return one(script.get(i), call);
                }
                return one(script, call);
            }

            private String one(JsonNode n, int call) {
                if (n.isObject() && n.has("error")) {
                    try {
                        throw Failures.tool(n.get("error").asText(), name, call);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                if (n.isObject() && n.has("returns")) return text(n.get("returns"));
                return text(n);
            }

            private String unscripted(int call, String why) {
                String message = "tool " + name + " " + why + " (call " + call + ")";
                recorder.violation(message);
                throw new IllegalStateException(message);
            }
        };
    }

    private static String text(JsonNode n) {
        return n.isTextual() ? n.asText() : n.toString();
    }

    /** Where an acting tool would have sent or written, as the script wrote it (never a secret). */
    private static String target(ToolDef d) {
        for (String key :
                new String[] {"url", "base_url", "to", "path", "root", "command", "dsn"}) {
            ToolDef.OptionValue v = d.getOptions().get(key);
            if (v != null) return v.toString();
        }
        return "";
    }

    private static String digest(Map<String, Object> args) {
        try {
            return Values.hash(JSON.writeValueAsString(new TreeMap<>(args)));
        } catch (Exception e) {
            return Values.hash(String.valueOf(args));
        }
    }
}
