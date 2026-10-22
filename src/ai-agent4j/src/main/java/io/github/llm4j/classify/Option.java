package io.github.llm4j.classify;

/**
 * One label of a {@link Question.Choice}, with an optional description of when it applies. A
 * backend that takes descriptions (Jev, and the prompt {@link LlmClassifier} builds) gets them; the
 * label alone is sent when there is none.
 *
 * @param label the value the answer carries; not blank
 * @param description when the label applies; {@code ""} when there is none
 */
public record Option(String label, String description) {

    public Option {
        if (label == null || label.isBlank())
            throw new IllegalArgumentException("an option needs a label");
        description = description == null ? "" : description;
    }

    /** A label with no description. */
    public static Option of(String label) {
        return new Option(label, "");
    }
}
