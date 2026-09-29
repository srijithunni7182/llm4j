package io.github.llm4j.eval.report;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * A JUnit 5 extension that reports on a test class evaluating agents, without requiring a separate
 * "runner" call. Apply it with {@code @ExtendWith(EvalReportExtension.class)}. It:
 *
 * <ul>
 *   <li>prints a pass/fail summary table (plus per-metric scores when judged conditions ran) in
 *       {@code afterAll};
 *   <li>activates {@link EvalRecorder} so every judged evaluation is captured with the test that
 *       ran it;
 *   <li>when the {@code eval4j.report.dir} system property is set, writes {@code
 *       eval4j-report.json} and a self-contained {@code eval4j-report.html} once per JVM, and
 *       appends the run's per-metric averages to a score history ({@code eval4j.history.file},
 *       default {@code <report dir>/eval4j-history.jsonl});
 *   <li>if the class is annotated with {@link EvalBaseline}, fails it when a metric regressed
 *       versus the checked-in baseline ({@code -Deval4j.baseline.update=true} rewrites the baseline
 *       instead).
 * </ul>
 *
 * <p>It depends only on the JUnit 5 extension API, so it works the same whether a test failed via
 * an eval4j condition, a plain {@code assertEquals}, or anything else.
 */
public class EvalReportExtension
        implements TestWatcher,
                BeforeAllCallback,
                BeforeEachCallback,
                AfterEachCallback,
                AfterAllCallback {

    public static final String REPORT_DIR_PROPERTY = "eval4j.report.dir";
    public static final String HISTORY_FILE_PROPERTY = "eval4j.history.file";
    public static final String BASELINE_UPDATE_PROPERTY = "eval4j.baseline.update";

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(EvalReportExtension.class);

    /** Per-JVM (per-engine-run) state; its {@code close()} writes the run-level report. */
    private static final class RunState
            implements ExtensionContext.Store.CloseableResource, AutoCloseable {
        final String runId = UUID.randomUUID().toString();
        final Instant startedAt = Instant.now();
        final Map<String, Double> baselineAverages = new TreeMap<>();

        @Override
        public void close() {
            try {
                writeRunReport();
            } finally {
                EvalRecorder.reset();
            }
        }

        private void writeRunReport() {
            String dir = System.getProperty(REPORT_DIR_PROPERTY);
            if (dir == null || dir.isBlank()) {
                return;
            }
            List<EvalRecord> records = EvalRecorder.records();
            Path reportDir = Path.of(dir);
            Path historyFile =
                    Path.of(
                            System.getProperty(
                                    HISTORY_FILE_PROPERTY,
                                    reportDir.resolve("eval4j-history.jsonl").toString()));
            ScoreHistory history = new FileSystemScoreHistory(historyFile);
            List<HistoryEntry> prior = history.load();
            String sha = gitSha();
            EvalReportWriter.RunInfo info =
                    new EvalReportWriter.RunInfo(
                            runId, startedAt.toString(), Instant.now().toString(), sha, records);
            EvalReportWriter.write(reportDir, info, prior, baselineAverages);
            if (!records.isEmpty()) {
                history.append(
                        new HistoryEntry(
                                runId,
                                info.endedAt(),
                                sha,
                                EvalReportWriter.metricAverages(records)));
            }
        }
    }

    private static String gitSha() {
        String sha = System.getenv("GITHUB_SHA");
        if (sha == null || sha.isBlank()) {
            sha = System.getProperty("git.commit.id");
        }
        return sha == null || sha.isBlank() ? null : sha;
    }

    private static RunState runState(ExtensionContext context) {
        return context.getRoot()
                .getStore(NAMESPACE)
                .getOrComputeIfAbsent("run", k -> new RunState(), RunState.class);
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        runState(context);
        EvalRecorder.activate();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        EvalRecorder.setCurrentTest(
                context.getRequiredTestClass().getName(), context.getDisplayName());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        EvalRecorder.clearCurrentTest();
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        outcomesFor(context).add(new Outcome(context.getDisplayName(), true, null));
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        outcomesFor(context).add(new Outcome(context.getDisplayName(), false, cause.getMessage()));
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        outcomesFor(context)
                .add(
                        new Outcome(
                                context.getDisplayName(),
                                false,
                                "aborted: " + (cause == null ? "" : cause.getMessage())));
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        // Disabled tests were never evaluated, so they don't belong in a pass/fail eval report.
    }

    @Override
    public void afterAll(ExtensionContext context) {
        List<Outcome> outcomes = outcomesFor(context);
        String suite = context.getRequiredTestClass().getName();
        List<EvalRecord> mine =
                EvalRecorder.records().stream().filter(r -> suite.equals(r.suite())).toList();
        printReport(context.getDisplayName(), outcomes, mine);
        checkBaseline(context, suite, mine);
    }

    private void checkBaseline(ExtensionContext context, String suite, List<EvalRecord> mine) {
        EvalBaseline config = context.getRequiredTestClass().getAnnotation(EvalBaseline.class);
        if (config == null) {
            return;
        }
        Path file = Path.of(config.file());
        Map<String, Double> current = BaselineGate.aggregate(mine);
        if (Boolean.getBoolean(BASELINE_UPDATE_PROPERTY)) {
            BaselineGate.update(file, suite, current);
            System.out.println("eval4j: baseline updated: " + file);
            return;
        }
        if (!Files.exists(file)) {
            throw new AssertionError(
                    "eval4j baseline file not found: "
                            + file
                            + ". Create it by running the tests with -D"
                            + BASELINE_UPDATE_PROPERTY
                            + "=true.");
        }
        Map<String, Double> baseline = BaselineGate.load(file);
        runState(context)
                .baselineAverages
                .putAll(EvalReportWriter.metricAverages(baselineRecords(baseline, suite)));
        BaselineGate.GateResult result =
                BaselineGate.compare(
                        baseline, current, suite, config.granularity(), config.maxRegression());
        for (String fresh : result.noBaseline()) {
            System.out.println("eval4j: no baseline yet for " + fresh);
        }
        if (!result.passed()) {
            throw new AssertionError(
                    "eval4j regression gate failed for "
                            + suite
                            + " ("
                            + result.regressions().size()
                            + " regressed):\n  "
                            + String.join("\n  ", result.regressions()));
        }
    }

    /** Baseline entries for {@code suite} re-expressed as records, to reuse the averaging code. */
    private static List<EvalRecord> baselineRecords(Map<String, Double> baseline, String suite) {
        List<EvalRecord> out = new CopyOnWriteArrayList<>();
        baseline.forEach(
                (k, v) -> {
                    if (k.startsWith(suite + "#")) {
                        String metric = k.substring(k.lastIndexOf('#') + 1);
                        out.add(new EvalRecord(suite, null, metric, v, 0, true, null, null, null));
                    }
                });
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Outcome> outcomesFor(ExtensionContext context) {
        String key = context.getRequiredTestClass().getName();
        ExtensionContext.Store store = context.getRoot().getStore(NAMESPACE);
        return (List<Outcome>)
                store.getOrComputeIfAbsent(
                        "outcomes:" + key, k -> new CopyOnWriteArrayList<Outcome>());
    }

    private void printReport(String className, List<Outcome> outcomes, List<EvalRecord> records) {
        long passed = outcomes.stream().filter(Outcome::passed).count();
        System.out.println();
        System.out.println("=== eval4j report: " + className + " ===");
        for (Outcome outcome : outcomes) {
            if (outcome.passed()) {
                System.out.printf("[PASS] %s%n", outcome.testName());
            } else {
                System.out.printf("[FAIL] %s - %s%n", outcome.testName(), outcome.failureMessage());
            }
        }
        System.out.printf("%d/%d passed%n", passed, outcomes.size());
        Map<String, Double> averages = EvalReportWriter.metricAverages(records);
        if (!averages.isEmpty()) {
            System.out.println("metric averages:");
            averages.forEach(
                    (metric, avg) -> System.out.printf(Locale.ROOT, "  %s: %.3f%n", metric, avg));
        }
        System.out.println();
    }

    private record Outcome(String testName, boolean passed, String failureMessage) {}
}
