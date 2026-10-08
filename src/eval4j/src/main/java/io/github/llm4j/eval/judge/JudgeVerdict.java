package io.github.llm4j.eval.judge;

/**
 * The score and reasoning a judge {@link io.github.llm4j.LLMClient} returned for one evaluation.
 * Public so a custom {@link JudgeCache} implementation can be written outside this package.
 */
public record JudgeVerdict(double score, String reason) {

    public JudgeVerdict {
        if (score < 0.0 || score > 1.0) {
            throw new JudgeEvaluationException(
                    "Judge returned an out-of-range score: "
                            + score
                            + " (expected between 0.0 and 1.0)");
        }
    }
}
