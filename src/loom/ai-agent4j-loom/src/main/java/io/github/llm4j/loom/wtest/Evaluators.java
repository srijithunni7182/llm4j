package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scripts an evaluator in {@code weave test} (docs/specs/26-policy-boundary, R13): a case says, per
 * rule, whether the text breaks it, {@code evaluators: { Compliance: { no_refund_promise: violated }
 * }}. A rule left out is answered {@code clean}; a list gives one such map per call. The answer
 * becomes the JSON an evaluator model would send, so the real evaluator code runs.
 */
final class Evaluators {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private Evaluators() {}

    /** The case's {@code evaluators:} as scripted model answers, by evaluator name. */
    static Map<String, JsonNode> scripted(JsonNode evaluators) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (evaluators == null || !evaluators.isObject()) return out;
        for (Iterator<Map.Entry<String, JsonNode>> it = evaluators.fields(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue().isArray()) {
                ArrayNode calls = F.arrayNode();
                e.getValue().forEach(one -> calls.add(answer(one)));
                out.put(e.getKey(), calls);
            } else {
                out.put(e.getKey(), answer(e.getValue()));
            }
        }
        return out;
    }

    private static ObjectNode answer(JsonNode rules) {
        ObjectNode out = F.objectNode();
        if (!rules.isObject()) return out;
        rules.fields()
                .forEachRemaining(
                        r -> {
                            String word = r.getValue().asText("clean");
                            ObjectNode one = out.putObject(r.getKey());
                            one.put(
                                    "violated",
                                    switch (word) {
                                        case "violated" -> "yes";
                                        case "unsure" -> "unsure";
                                        default -> "no";
                                    });
                            one.put("reason", "scripted: " + word);
                        });
        return out;
    }
}
