package io.github.llm4j.agent.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a {@link Task} returns. A workflow variable bound with {@code run Task(...) -> name} holds {@link #toMap()}:
 * {@code outcome} (default {@code "ok"}), {@code reason} when there is one, {@code value} when there is one, and any data entries.
 * So a script can branch on {@code name.outcome} and print {@code {name.reason}}.
 *
 * <p>Immutable. Every value is checked at construction to be something the run journal can store as JSON: strings, numbers,
 * booleans, null, maps with string keys and lists.
 */
public final class TaskResult {

    /** What an outcome name looks like: lower case, digits and underscores. */
    public static final Pattern OUTCOME = Pattern.compile("[a-z][a-z0-9_]*");

    /** Keys the result itself owns; data entries may not use them. */
    public static final java.util.Set<String> RESERVED = java.util.Set.of("outcome", "reason", "value");

    private final String outcome;
    private final String reason;
    private final boolean hasValue;
    private final Object value;
    private final Map<String, Object> data;

    private TaskResult(String outcome, String reason, boolean hasValue, Object value, Map<String, Object> data) {
        if (outcome == null || !OUTCOME.matcher(outcome).matches()) {
            throw new IllegalArgumentException("outcome \"" + outcome + "\" must be lower case letters, digits and underscores, starting with a letter");
        }
        this.outcome = outcome;
        this.reason = reason;
        this.hasValue = hasValue;
        this.value = value;
        this.data = data;
    }

    /** Outcome {@code ok}, nothing else. */
    public static TaskResult ok() {
        return new TaskResult("ok", null, false, null, Map.of());
    }

    /** Outcome {@code ok} with data entries (copied; keys {@code outcome}, {@code reason} and {@code value} are reserved). */
    public static TaskResult ok(Map<String, ?> data) {
        return ok().withAll(data);
    }

    /** Outcome {@code ok} with a single {@code value}. */
    public static TaskResult value(Object value) {
        return ok().withValue(value);
    }

    /** Outcome {@code rejected} with a reason: the task decided the answer is no. */
    public static TaskResult rejected(String reason) {
        return outcome("rejected").reason(reason);
    }

    /** Any outcome, such as {@code approved}, {@code needs_review} or {@code not_found}. */
    public static TaskResult outcome(String outcome) {
        return new TaskResult(outcome, null, false, null, Map.of());
    }

    /** The same result with a reason. */
    public TaskResult reason(String reason) {
        return new TaskResult(outcome, reason, hasValue, value, data);
    }

    /** The same result with one more data entry. */
    public TaskResult with(String key, Object entry) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("a data key must not be blank");
        if (RESERVED.contains(key)) throw new IllegalArgumentException("\"" + key + "\" is reserved; use outcome(), reason() or withValue()");
        Map<String, Object> next = new LinkedHashMap<>(data);
        next.put(key, TaskValues.jsonSafe(entry, "data \"" + key + "\""));
        return new TaskResult(outcome, reason, hasValue, value, Collections.unmodifiableMap(next));
    }

    /** The same result with several more data entries. */
    public TaskResult withAll(Map<String, ?> entries) {
        TaskResult next = this;
        if (entries != null) {
            for (Map.Entry<String, ?> e : entries.entrySet()) next = next.with(e.getKey(), e.getValue());
        }
        return next;
    }

    /** The same result with a {@code value}. */
    public TaskResult withValue(Object value) {
        return new TaskResult(outcome, reason, true, TaskValues.jsonSafe(value, "value"), data);
    }

    public String outcome() {
        return outcome;
    }

    /** The reason, or null. */
    public String reason() {
        return reason;
    }

    /** The value, or null (also null when it was set to null; see {@link #hasValue()}). */
    public Object value() {
        return value;
    }

    public boolean hasValue() {
        return hasValue;
    }

    /** The data entries, unmodifiable. */
    public Map<String, Object> data() {
        return data;
    }

    /** What a workflow variable holds: {@code outcome}, then {@code reason}, {@code value} and the data entries, when present. Unmodifiable. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("outcome", outcome);
        if (reason != null) map.put("reason", reason);
        if (hasValue) map.put("value", value);
        map.putAll(data);
        return Collections.unmodifiableMap(map);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TaskResult r && toMap().equals(r.toMap());
    }

    @Override
    public int hashCode() {
        return Objects.hash(toMap());
    }

    @Override
    public String toString() {
        return "TaskResult" + toMap();
    }
}
