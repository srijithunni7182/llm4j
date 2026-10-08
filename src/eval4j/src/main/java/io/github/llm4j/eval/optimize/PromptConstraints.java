package io.github.llm4j.eval.optimize;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Cheap checks applied to a proposed candidate <em>before</em> any rollout is spent on it: maximum
 * length, substrings that must (or must not) appear in every parameter, and an optional custom
 * predicate. Constraints stop a rewriter from bloating a prompt or dropping a required template
 * placeholder.
 */
public final class PromptConstraints {

    private static final PromptConstraints NONE = builder().build();

    private final int maxChars;
    private final List<String> mustContain;
    private final List<String> mustNotContain;
    private final Predicate<Candidate> custom;
    private final String customDescription;

    private PromptConstraints(Builder b) {
        this.maxChars = b.maxChars;
        this.mustContain = List.copyOf(b.mustContain);
        this.mustNotContain = List.copyOf(b.mustNotContain);
        this.custom = b.custom;
        this.customDescription = b.customDescription;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static PromptConstraints none() {
        return NONE;
    }

    /** Returns a description of the first violated constraint, or {@code null} if none. */
    public String violation(Candidate candidate) {
        for (var entry : candidate.parameters().entrySet()) {
            String name = entry.getKey();
            String text = entry.getValue();
            if (maxChars > 0 && text.length() > maxChars) {
                return "parameter \""
                        + name
                        + "\" is "
                        + text.length()
                        + " characters (max "
                        + maxChars
                        + ")";
            }
            for (String required : mustContain) {
                if (!text.contains(required)) {
                    return "parameter \"" + name + "\" is missing required text: " + required;
                }
            }
            for (String forbidden : mustNotContain) {
                if (text.contains(forbidden)) {
                    return "parameter \"" + name + "\" contains forbidden text: " + forbidden;
                }
            }
        }
        if (custom != null && !custom.test(candidate)) {
            return customDescription;
        }
        return null;
    }

    public static final class Builder {
        private int maxChars;
        private final List<String> mustContain = new ArrayList<>();
        private final List<String> mustNotContain = new ArrayList<>();
        private Predicate<Candidate> custom;
        private String customDescription = "custom constraint failed";

        public Builder maxChars(int maxChars) {
            if (maxChars < 1) {
                throw new IllegalArgumentException("maxChars must be positive");
            }
            this.maxChars = maxChars;
            return this;
        }

        /**
         * Text every parameter must contain, e.g. a template placeholder such as {@code {{input}}}.
         */
        public Builder mustContain(String... texts) {
            mustContain.addAll(List.of(texts));
            return this;
        }

        public Builder mustNotContain(String... texts) {
            mustNotContain.addAll(List.of(texts));
            return this;
        }

        public Builder predicate(String description, Predicate<Candidate> predicate) {
            this.customDescription = description;
            this.custom = predicate;
            return this;
        }

        public PromptConstraints build() {
            return new PromptConstraints(this);
        }
    }
}
