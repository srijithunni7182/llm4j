package io.github.llm4j.eval.criteria;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a check found, as data a {@link Criterion} can read (phase 35, D17): the {@code verify}
 * tool's record of a test report or a schema check, for one. The counts and the status are facts
 * the tool parsed and may be trusted; the item texts (test names, messages) may have been written by
 * a model and stay unchecked: a criterion compares counts, never the wording.
 *
 * @param kind what produced it, e.g. {@code junit} or {@code schema}
 * @param status {@code passed}, {@code failed} or {@code empty}
 * @param counts named counts, e.g. {@code total}, {@code passed}, {@code failed}
 * @param uncheckedItems the item texts, as written; never a basis for a verdict on their own
 */
public record Evidence(String kind, String status, Map<String, Long> counts, List<String> uncheckedItems) {

    /** Checks nothing is missing and copies the collections. */
    public Evidence {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        counts = Map.copyOf(counts);
        uncheckedItems = List.copyOf(uncheckedItems);
    }

    /**
     * The evidence in a {@code verify} result, as the tool returns it (parsed JSON: {@code format},
     * {@code status}, {@code total}, {@code passed}, {@code failed}, {@code errors}, {@code
     * skipped}, {@code items}). A result it cannot read is a failure with one error, never a pass.
     */
    public static Evidence fromVerify(Map<String, ?> result) {
        if (result == null || !(result.get("status") instanceof String status))
            return new Evidence("unreadable", "failed", Map.of("failed", 1L, "errors", 1L), List.of());
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String k : List.of("total", "passed", "failed", "errors", "skipped"))
            if (result.get(k) instanceof Number n) counts.put(k, n.longValue());
        List<String> items = new java.util.ArrayList<>();
        if (result.get("items") instanceof List<?> list)
            for (Object item : list)
                if (item instanceof Map<?, ?> m) {
                    Object name = m.get("name");
                    Object message = m.get("message");
                    items.add(String.valueOf(name) + (message == null ? "" : ": " + message));
                }
        Object format = result.get("format");
        return new Evidence(format == null ? "verify" : String.valueOf(format), status, counts, items);
    }

    /** A count, or zero when the check did not give it. */
    public long count(String name) {
        return counts.getOrDefault(name, 0L);
    }

    /** Whether the check passed: its status says so and nothing failed or errored. */
    public boolean passed() {
        return status.equals("passed") && count("failed") == 0 && count("errors") == 0;
    }
}
