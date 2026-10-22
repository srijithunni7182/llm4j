package io.github.llm4j.classify;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The answer to a {@link Question.Score}: a point along the question's scale.
 *
 * @param position where along the scale, from 0.0 (first point) to 1.0 (last); fractional when the
 *     backend averages levels
 * @param nearest the scale point closest to the position
 * @param probabilities a probability per scale point; empty when the backend gave none
 * @param confidence see {@link ChoiceAnswer#confidence()}
 * @param calibrated see {@link Answer#calibrated()}
 * @param reason the one-sentence reason, or {@code ""}
 */
public record ScoreAnswer(
        double position,
        String nearest,
        Map<String, Double> probabilities,
        OptionalDouble confidence,
        boolean calibrated,
        String reason)
        implements Answer {

    public ScoreAnswer {
        Answer.unit(position, "position");
        if (nearest == null || nearest.isBlank())
            throw new IllegalArgumentException("an answer needs its nearest scale point");
        probabilities = Answer.unitMap(probabilities, "probability");
        confidence = Answer.unitOpt(confidence, "confidence");
        reason = reason == null ? "" : reason;
    }

    public ScoreAnswer(
            double position,
            String nearest,
            Map<String, Double> probabilities,
            OptionalDouble confidence,
            boolean calibrated) {
        this(position, nearest, probabilities, confidence, calibrated, "");
    }

    @Override
    public Optional<String> rationale() {
        return reason.isEmpty() ? Optional.empty() : Optional.of(reason);
    }
}
