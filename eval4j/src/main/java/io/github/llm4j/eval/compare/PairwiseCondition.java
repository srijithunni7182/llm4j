package io.github.llm4j.eval.compare;

import io.github.llm4j.eval.report.EvalRecorder;
import java.util.Objects;
import org.assertj.core.api.Condition;
import org.assertj.core.description.Description;
import org.assertj.core.description.TextDescription;

/**
 * Per-case comparison as an AssertJ condition over a {@link ComparisonPair}: passes when output B
 * (the candidate) is at least as good as A — it wins, or ties. Use it when you prefer writing your
 * own loop over {@link PromptComparison}.
 */
public final class PairwiseCondition extends Condition<Object> {

    private final PairwiseJudge judge;
    private final String metricName;
    private final ThreadLocal<Description> perThreadDescription = new ThreadLocal<>();

    public PairwiseCondition(PairwiseJudge judge, String metricName) {
        super("candidate (B) is at least as good as baseline (A)");
        this.judge = Objects.requireNonNull(judge, "judge cannot be null");
        this.metricName = metricName == null ? "Pairwise" : metricName;
    }

    @Override
    public Description description() {
        Description perThread = perThreadDescription.get();
        return perThread != null ? perThread : super.description();
    }

    @Override
    public boolean matches(Object actual) {
        if (!(actual instanceof ComparisonPair pair)) {
            throw new IllegalArgumentException(
                    "Expected a ComparisonPair but got: "
                            + (actual == null ? "null" : actual.getClass().getName()));
        }
        long startedNanos = System.nanoTime();
        PairwiseJudge.PairResult result = judge.judge(pair.input(), pair.a(), pair.b());
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
        double score =
                switch (result.winner()) {
                    case B -> 1.0;
                    case TIE -> 0.5;
                    case A -> 0.0;
                };
        perThreadDescription.set(
                new TextDescription(
                        "%s: winner=%s. %s", metricName, result.winner(), result.reason()));
        EvalRecorder.record(
                metricName,
                score,
                0.5,
                result.reason(),
                null,
                new io.github.llm4j.eval.report.EvalDetails(
                        pair.input(),
                        "A: " + pair.a() + "\n\nB: " + pair.b(),
                        null,
                        null,
                        elapsedMs));
        return result.winner() != PairwiseJudge.Winner.A;
    }
}
