package io.github.llm4j.eval.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Builds a realistic demo dashboard (a support-bot RAG evaluation with a run history) into {@code
 * target/eval4j-sample}. Used for the screenshots and the checked-in sample; it also exercises the
 * whole pipeline on a mid-sized, varied report.
 */
class SampleReportTest {

    static final Path OUT = Path.of("target/eval4j-sample");

    private static final String[] METRICS = {
        "Faithfulness", "Answer Relevancy", "Contextual Precision", "Tone"
    };
    private static final String[] TESTS = {
        "refund window",
        "shipping to Canada",
        "cancel subscription",
        "reset password",
        "invoice copy",
        "change delivery address",
        "warranty length",
        "student discount",
        "bulk order pricing",
        "gift wrapping",
        "damaged item",
        "lost parcel",
        "price match",
        "payment methods",
        "account deletion",
        "two-factor setup",
        "loyalty points",
        "store hours"
    };

    static EvalReportWriter.RunInfo sampleRun(String id, long seed, double quality, int day) {
        Random rnd = new Random(seed);
        Random fixed = new Random(42); // each case keeps its own difficulty from run to run
        List<EvalRecord> records = new ArrayList<>();
        for (String test : TESTS) {
            for (String metric : METRICS) {
                double base = quality - (metric.equals("Contextual Precision") ? 0.12 : 0.0);
                double score =
                        Math.max(
                                0,
                                Math.min(
                                        1,
                                        base
                                                + fixed.nextGaussian() * 0.15
                                                + rnd.nextGaussian() * 0.04));
                score = Math.round(score * 100) / 100.0;
                double threshold = metric.equals("Tone") ? 0.6 : 0.7;
                boolean ok = score >= threshold;
                String reason =
                        ok
                                ? "The answer is consistent with the retrieved policy text and addresses the question directly."
                                : "The answer states a figure that does not appear in any retrieved chunk, so part of it cannot be verified from the context.";
                records.add(
                        new EvalRecord(
                                "com.acme.support.SupportBotEvalTest",
                                test,
                                metric,
                                score,
                                threshold,
                                ok,
                                reason,
                                "claude-judge",
                                "2026-09-%02dT10:00:00Z".formatted(day),
                                "Customer: What is your policy on " + test + "?",
                                "Our policy on "
                                        + test
                                        + " is described in the help centre; most requests are handled within 5 business days.",
                                "Policy: " + test + " requests are handled within 14 days.",
                                List.of(
                                        "Help centre / "
                                                + test
                                                + ": requests are handled within 14 days of purchase.",
                                        "Help centre / contact: reach support 24/7 by chat."),
                                400L + rnd.nextInt(1800)));
            }
        }
        List<TestOutcome> tests = new ArrayList<>();
        for (String t : TESTS) {
            tests.add(
                    new TestOutcome(
                            "com.acme.support.SupportBotEvalTest",
                            t,
                            "PASSED",
                            900L + rnd.nextInt(3000),
                            null));
        }
        tests.set(
                3,
                new TestOutcome(
                        "com.acme.support.SupportBotEvalTest",
                        "reset password",
                        "FAILED",
                        2100,
                        "Expecting actual: 0.41 to be greater than or equal to: 0.7 (Faithfulness)"));
        return new EvalReportWriter.RunInfo(
                id,
                "2026-09-%02dT10:00:00Z".formatted(day),
                "2026-09-%02dT10:02:41Z".formatted(day),
                "9f2c4e1ab7d3085ce61f2a90b4d7e8c31a5f6b02",
                records,
                tests);
    }

    @Test
    void buildsTheSampleDashboard() throws Exception {
        List<HistoryEntry> history = new ArrayList<>();
        double[] quality = {0.71, 0.74, 0.73, 0.78, 0.80, 0.79, 0.84};
        for (int i = 0; i < quality.length; i++) {
            history.add(
                    EvalReportWriter.historyEntry(
                            sampleRun("run-" + i, 100 + i, quality[i], 1 + i)));
        }
        EvalReportWriter.RunInfo current = sampleRun("run-7", 107, 0.76, 9);
        Map<String, Double> baseline =
                EvalReportWriter.metricAverages(sampleRun("b", 105, 0.82, 6).records());
        EvalReportWriter.write(OUT, current, history, baseline);
        assertThat(Files.size(OUT.resolve("eval4j-report.html"))).isGreaterThan(20_000);
    }
}
