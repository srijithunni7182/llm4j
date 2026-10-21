package io.github.llm4j.classify;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/** The typed reply to one {@link Question}. */
public sealed interface Answer permits ChoiceAnswer, YesNoAnswer, ScoreAnswer {

    /**
     * Whether the probabilities are trained to match observed accuracy (Jev). A chat model's
     * self-reported number is not calibrated, whatever the model claims, so {@link LlmClassifier}
     * answers are never calibrated.
     */
    boolean calibrated();

    /**
     * The one-sentence reason, when it was asked for ({@link LlmClassifier.Builder#withRationale}).
     * Never recorded by telemetry.
     */
    Optional<String> rationale();

    /** Checks a probability or confidence: a number in [0,1]. */
    static double unit(double v, String what) {
        if (Double.isNaN(v) || v < 0.0 || v > 1.0)
            throw new IllegalArgumentException(what + " must be a number from 0 to 1, got " + v);
        return v;
    }

    static Map<String, Double> unitMap(Map<String, Double> m, String what) {
        Map<String, Double> copy = m == null ? Map.of() : Map.copyOf(m);
        copy.forEach((k, v) -> unit(v, what + " for " + k));
        return copy;
    }

    static OptionalDouble unitOpt(OptionalDouble v, String what) {
        v = v == null ? OptionalDouble.empty() : v;
        v.ifPresent(d -> unit(d, what));
        return v;
    }
}
