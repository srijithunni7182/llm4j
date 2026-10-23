package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.agent.ToolException;
import io.github.llm4j.exception.ContentBlockedException;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.exception.ServiceUnavailableException;
import io.github.llm4j.telemetry.ErrorCategory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Failures a case injects ({@code fail:}). A target fails on its first N calls ({@code times}), on
 * the calls it names ({@code on_calls}) or always, with one of the categories the runtime
 * distinguishes, so that {@code retry}, {@code on_failure}, {@code rewind} and the rate-limit
 * handling run for real.
 */
final class Failures {

    /** The categories a test file may name, and what each looks like to the runtime. */
    static final List<String> CATEGORIES =
            List.of(
                    "rate_limit",
                    "timeout",
                    "server_error",
                    "invalid_output",
                    "tool_error",
                    "refused",
                    "quota");

    private record Rule(Set<Integer> calls, Integer times, boolean always, String category) {
        boolean fails(int call) {
            return always || (times != null && call <= times) || calls.contains(call);
        }
    }

    private final Map<String, Rule> rules = new LinkedHashMap<>();

    Failures(JsonNode fail) {
        if (fail == null || !fail.isObject()) return;
        fail.fields()
                .forEachRemaining(
                        e -> {
                            JsonNode spec = e.getValue();
                            Set<Integer> calls = new HashSet<>();
                            if (spec.has("on_calls"))
                                spec.get("on_calls").forEach(n -> calls.add(n.asInt()));
                            rules.put(
                                    e.getKey(),
                                    new Rule(
                                            calls,
                                            spec.has("times") ? spec.get("times").asInt() : null,
                                            spec.has("always") && spec.get("always").asBoolean(),
                                            spec.has("category")
                                                    ? spec.get("category").asText()
                                                    : "server_error"));
                        });
    }

    /** The category the target fails with on this call, or empty when the call goes through. */
    Optional<String> failsOn(String target, int call) {
        Rule r = rules.get(target);
        return r != null && r.fails(call) ? Optional.of(r.category()) : Optional.empty();
    }

    /** The exception a model call fails with. */
    static RuntimeException model(String category, String target, int call) {
        String what =
                "scripted " + category.replace('_', ' ') + " (" + target + ", call " + call + ")";
        return switch (category) {
            case "rate_limit", "quota" -> new RateLimitException(what);
            case "timeout" -> new LLMException(what, new java.net.SocketTimeoutException(what));
            case "refused" -> new ContentBlockedException("scripted", what);
            default -> new ServiceUnavailableException("scripted", what);
        };
    }

    /** The exception a tool call fails with. */
    static Exception tool(String category, String target, int call) {
        String what =
                "scripted " + category.replace('_', ' ') + " (" + target + ", call " + call + ")";
        ErrorCategory c =
                switch (category) {
                    case "timeout" -> ErrorCategory.TIMEOUT;
                    case "rate_limit", "quota" -> ErrorCategory.RATE_LIMITED;
                    case "refused" -> ErrorCategory.PERMISSION_DENIED;
                    case "server_error" -> ErrorCategory.SERVER_ERROR;
                    default -> ErrorCategory.OTHER;
                };
        return new ToolException(c, what);
    }

    /** The targets that have a rule, for the file check. */
    List<String> targets() {
        return new ArrayList<>(rules.keySet());
    }
}
