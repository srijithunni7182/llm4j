package io.github.llm4j.eval.optimize;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable assignment of text to every optimizable parameter (e.g. {@code system-prompt}), plus
 * provenance. Two candidates are equal when their parameter text is equal, so a rewrite that
 * reproduces an existing prompt is detectable as a duplicate.
 */
public final class Candidate {

    public enum Origin {
        SEED,
        REWRITE
    }

    private final String id;
    private final String parentId;
    private final Origin origin;
    private final int round;
    private final Map<String, String> parameters;

    private Candidate(
            String id, String parentId, Origin origin, int round, Map<String, String> parameters) {
        this.id = id;
        this.parentId = parentId;
        this.origin = origin;
        this.round = round;
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    public static Candidate of(String name, String text) {
        return of(Map.of(requireName(name), Objects.requireNonNull(text, "text cannot be null")));
    }

    public static Candidate of(Map<String, String> parameters) {
        Objects.requireNonNull(parameters, "parameters cannot be null");
        if (parameters.isEmpty()) {
            throw new IllegalArgumentException("a candidate needs at least one parameter");
        }
        parameters.forEach(
                (k, v) -> {
                    requireName(k);
                    Objects.requireNonNull(v, "parameter text cannot be null: " + k);
                });
        return new Candidate("c0", null, Origin.SEED, 0, parameters);
    }

    /** A child of this candidate with one parameter replaced. */
    Candidate derive(String childId, String parameter, String newText, int round) {
        if (!parameters.containsKey(parameter)) {
            throw new IllegalArgumentException("unknown parameter: " + parameter);
        }
        Map<String, String> copy = new LinkedHashMap<>(parameters);
        copy.put(parameter, newText);
        return new Candidate(childId, id, Origin.REWRITE, round, copy);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("parameter name cannot be blank");
        }
        return name;
    }

    public String id() {
        return id;
    }

    public String parentId() {
        return parentId;
    }

    public Origin origin() {
        return origin;
    }

    public int round() {
        return round;
    }

    public Map<String, String> parameters() {
        return parameters;
    }

    public Set<String> parameterNames() {
        return parameters.keySet();
    }

    /** The text of a parameter; throws if the candidate has no such parameter. */
    public String get(String name) {
        String text = parameters.get(name);
        if (text == null) {
            throw new IllegalArgumentException("unknown parameter: " + name);
        }
        return text;
    }

    /** Total characters across parameters; used to prefer shorter prompts on ties. */
    public int totalLength() {
        return parameters.values().stream().mapToInt(String::length).sum();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Candidate c && parameters.equals(c.parameters);
    }

    @Override
    public int hashCode() {
        return parameters.hashCode();
    }

    @Override
    public String toString() {
        return "Candidate[" + id + ", " + origin + ", round " + round + "]";
    }
}
