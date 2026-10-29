package io.github.llm4j.loom.eval;

import io.github.llm4j.LLMClient;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.judge.JudgeCalls;
import io.github.llm4j.eval.judge.JudgeVerdict;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides whether a piece of text meets a judged expectation ({@code rubric} or {@code expect}
 * line).
 */
public interface RubricJudge {

    /** The verdict on {@code subject} against {@code criteria}, for the scenario it came from. */
    JudgeVerdict judge(String criteria, String subject, EvalScenario scenario);

    /**
     * A judge that asks a model; what it is shown besides the answer is the scenario's input and
     * context.
     */
    static RubricJudge model(LLMClient client, String identifier) {
        JudgeCalls calls = JudgeCalls.using(client).judgeIdentifier(identifier);
        return (criteria, subject, scenario) -> {
            Map<String, String> sections = new LinkedHashMap<>();
            if (scenario.input() != null) sections.put("Input", scenario.input());
            if (scenario.context() != null && !scenario.context().isEmpty())
                sections.put("Context", String.join("\n", scenario.context()));
            return calls.rate("rubric", criteria, subject, sections);
        };
    }

    /** The score a judged line needs to count as met. */
    double THRESHOLD = 0.7;

    /** The most samples a line may be judged with (each one is a paid call). */
    int MAX_SAMPLES = 9;

    /** Samples that differ by this much or more are not a verdict (two points on a five-point scale). */
    double UNSTABLE_SPREAD = 0.4;

    /** The samples of a line disagreed too much to decide it. */
    final class UnstableJudgement extends RuntimeException {
        public UnstableJudgement(String message) {
            super(message);
        }
    }

    /**
     * Judges each line {@code samples} times and answers with the median, so one lucky or unlucky
     * sample does not decide a line. Samples that spread by {@link #UNSTABLE_SPREAD} or more throw
     * {@link UnstableJudgement}: the line is then unjudged, not decided on a coin toss.
     */
    static RubricJudge sampled(RubricJudge judge, int samples) {
        if (samples < 1 || samples > MAX_SAMPLES)
            throw new IllegalArgumentException(
                    "judge samples must be between 1 and " + MAX_SAMPLES + ", got " + samples);
        if (samples == 1) return judge;
        return (criteria, subject, scenario) -> {
            java.util.List<Double> scores = new java.util.ArrayList<>();
            String reason = "";
            for (int i = 0; i < samples; i++) {
                JudgeVerdict v = judge.judge(criteria, subject, scenario);
                scores.add(v.score());
                reason = v.reason();
            }
            java.util.List<Double> sorted = scores.stream().sorted().toList();
            double spread = sorted.get(sorted.size() - 1) - sorted.get(0);
            String shown =
                    scores.stream()
                            .map(x -> String.format(java.util.Locale.ROOT, "%.2f", x))
                            .collect(java.util.stream.Collectors.joining(", "));
            if (spread >= UNSTABLE_SPREAD)
                throw new UnstableJudgement(
                        String.format(
                                java.util.Locale.ROOT,
                                "unstable: the %d judge samples (%s) differ by %.2f, so this line is not decided",
                                samples,
                                shown,
                                spread));
            int mid = sorted.size() / 2;
            double median =
                    sorted.size() % 2 == 1
                            ? sorted.get(mid)
                            : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
            return new JudgeVerdict(
                    median, "median of " + samples + " samples (" + shown + "): " + reason);
        };
    }
}
