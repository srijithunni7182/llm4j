package io.github.llm4j.classify;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The answer to a {@link Question.Choice}: one of the question's labels.
 *
 * @param label the chosen label, always one of the request's labels
 * @param probabilities a probability per label; empty when the backend gave none
 * @param confidence how sure the backend is: Jev's {@code confidence} field as returned (a
 *     statistic of the probabilities, not a chance of being right), a chat model's self-report, or
 *     a vote share; empty when there is none
 * @param calibrated see {@link Answer#calibrated()}
 * @param reason the one-sentence reason, or {@code ""}
 */
public record ChoiceAnswer(
        String label,
        Map<String, Double> probabilities,
        OptionalDouble confidence,
        boolean calibrated,
        String reason)
        implements Answer {

    public ChoiceAnswer {
        if (label == null || label.isBlank())
            throw new IllegalArgumentException("an answer needs a label");
        probabilities = Answer.unitMap(probabilities, "probability");
        confidence = Answer.unitOpt(confidence, "confidence");
        reason = reason == null ? "" : reason;
    }

    public ChoiceAnswer(
            String label,
            Map<String, Double> probabilities,
            OptionalDouble confidence,
            boolean calibrated) {
        this(label, probabilities, confidence, calibrated, "");
    }

    @Override
    public Optional<String> rationale() {
        return reason.isEmpty() ? Optional.empty() : Optional.of(reason);
    }
}
