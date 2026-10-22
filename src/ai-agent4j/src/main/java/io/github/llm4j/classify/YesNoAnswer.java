package io.github.llm4j.classify;

import java.util.Optional;

/**
 * The answer to a {@link Question.YesNo}.
 *
 * @param probability the probability of yes, from 0 to 1
 * @param calibrated see {@link Answer#calibrated()}
 * @param reason the one-sentence reason, or {@code ""}
 */
public record YesNoAnswer(double probability, boolean calibrated, String reason) implements Answer {

    public YesNoAnswer {
        Answer.unit(probability, "probability");
        reason = reason == null ? "" : reason;
    }

    public YesNoAnswer(double probability, boolean calibrated) {
        this(probability, calibrated, "");
    }

    /** Whether the answer leans yes (probability at least 0.5). */
    public boolean yes() {
        return probability >= 0.5;
    }

    /**
     * How far the answer is from a coin toss: {@code max(p, 1-p)}, from 0.5 (no idea) to 1.0
     * (certain). A yes/no answer carries no separate confidence.
     */
    public double certainty() {
        return Math.max(probability, 1.0 - probability);
    }

    @Override
    public Optional<String> rationale() {
        return reason.isEmpty() ? Optional.empty() : Optional.of(reason);
    }
}
