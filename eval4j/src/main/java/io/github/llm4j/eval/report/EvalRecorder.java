package io.github.llm4j.eval.report;

import io.github.llm4j.eval.export.EvalRun;
import io.github.llm4j.eval.export.Evaluation;
import io.github.llm4j.eval.export.JudgeTelemetry;
import io.github.llm4j.eval.export.MetricRef;
import io.github.llm4j.eval.export.Source;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Collects one {@link EvalRecord} per judged evaluation so {@link EvalReportExtension} can write
 * reports and check regression baselines. Recording is a no-op unless the recorder is active —
 * {@link EvalReportExtension} activates it — so code that never applies the extension behaves
 * exactly as before.
 *
 * <p>State is global to the JVM and thread-safe. The current test's identity is tracked per thread
 * (set by the extension around each test), so parallel tests attribute records correctly.
 */
public final class EvalRecorder {

    private static final List<EvalRecord> RECORDS = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<String[]> CURRENT_TEST = new ThreadLocal<>();
    private static volatile boolean active;
    private static volatile Supplier<Instant> clock = Instant::now;

    private EvalRecorder() {}

    public static void activate() {
        active = true;
    }

    public static void deactivate() {
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    /** Clears all records and deactivates. Mainly for tests. */
    public static void reset() {
        RECORDS.clear();
        CURRENT_TEST.remove();
        active = false;
        clock = Instant::now;
    }

    /** Injects a clock (for deterministic reports in tests). */
    public static void setClock(Supplier<Instant> newClock) {
        clock = newClock == null ? Instant::now : newClock;
    }

    static void setCurrentTest(String suite, String testName) {
        CURRENT_TEST.set(new String[] {suite, testName});
        EvalRun.get().bindTest(suite, testName);
        JudgeTelemetry.drain();
    }

    static void clearCurrentTest() {
        CURRENT_TEST.remove();
        EvalRun.get().unbind();
    }

    /** Records one evaluation; no-op when the recorder is not active. */
    public static void record(
            String metric, double score, double threshold, String reason, String judgeIdentifier) {
        record(metric, score, threshold, reason, judgeIdentifier, EvalDetails.NONE);
    }

    /**
     * Records one evaluation together with the case behind it (input, output, retrieved context);
     * no-op when the recorder is not active.
     */
    public static void record(
            String metric,
            double score,
            double threshold,
            String reason,
            String judgeIdentifier,
            EvalDetails details) {
        record(metric, score, threshold, reason, judgeIdentifier, details, null);
    }

    /**
     * Records the result of an A/B comparison: score 1 when B wins, 0.5 for a tie, 0 when A wins.
     * It is exported as a {@code PAIRWISE} evaluation of the prompts family.
     */
    public static void recordPairwise(
            String metric, double score, double threshold, String reason, EvalDetails details) {
        MetricRef ref =
                MetricRef.of(metric)
                        .kind(io.github.llm4j.eval.export.Kind.PAIRWISE)
                        .family("prompts")
                        .facet("compare")
                        .dimension("prompting");
        record(metric, score, threshold, reason, null, details, ref);
    }

    private static void record(
            String metric,
            double score,
            double threshold,
            String reason,
            String judgeIdentifier,
            EvalDetails details,
            MetricRef forced) {
        if (!active) {
            return;
        }
        EvalDetails d = details == null ? EvalDetails.NONE : details.bounded();
        exportEvaluation(metric, score, threshold, reason, judgeIdentifier, d, forced);
        String[] test = CURRENT_TEST.get();
        RECORDS.add(
                new EvalRecord(
                        test == null ? null : test[0],
                        test == null ? null : test[1],
                        metric,
                        score,
                        threshold,
                        score >= threshold,
                        reason,
                        judgeIdentifier,
                        clock.get().toString(),
                        d.input(),
                        d.actualOutput(),
                        d.expectedOutput(),
                        d.retrievalContext(),
                        d.durationMs()));
    }

    private static void exportEvaluation(
            String metric,
            double score,
            double threshold,
            String reason,
            String judgeIdentifier,
            EvalDetails d,
            MetricRef forced) {
        EvalRun run = EvalRun.get();
        if (!run.isExporting()) {
            return;
        }
        MetricRef ref = (forced != null ? forced : MetricRef.of(metric)).threshold(threshold);
        EvalRun.MetricOverride o = run.override();
        if (o != null) {
            ref = o.metric();
        }
        Evaluation.Builder b =
                Evaluation.builder(ref)
                        .score(score)
                        .threshold(threshold)
                        .reason(reason)
                        .details(
                                d.input(),
                                d.actualOutput(),
                                d.expectedOutput(),
                                d.retrievalContext())
                        .durationMs(d.durationMs());
        JudgeTelemetry.Usage usage = JudgeTelemetry.drain();
        if (judgeIdentifier != null && !judgeIdentifier.isBlank()) {
            String judgeId = MetricRef.slug(judgeIdentifier);
            run.noteJudge(judgeId, judgeIdentifier);
            b.judgeId(judgeId);
        }
        if (usage.calls() > 0 || usage.cacheHits() > 0) {
            b.usage(usage.calls(), usage.tokensIn(), usage.tokensOut(), null);
            if (usage.servedFromCache()) {
                b.source(Source.REUSED);
            }
            if (b.durationMsUnset() && usage.latencyMs() > 0) {
                b.durationMs(usage.latencyMs());
            }
        }
        run.record(b);
    }

    /** A snapshot of everything recorded so far, in recording order. */
    public static List<EvalRecord> records() {
        return new ArrayList<>(RECORDS);
    }
}
