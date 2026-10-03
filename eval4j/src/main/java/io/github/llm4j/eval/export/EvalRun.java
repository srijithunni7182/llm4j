package io.github.llm4j.eval.export;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The run being exported by this JVM: a singleton that records evaluations, tests and traces into a
 * run bundle and finishes it (writes {@code run.json}, appends to {@code index.jsonl}, notifies
 * {@link RunExportListener}s) when the JVM's test run ends.
 *
 * <p>Evaluations are attributed to the current case: the scenario bound by the extension, else the
 * current test.
 */
public final class EvalRun {

    private static final EvalRun INSTANCE = new EvalRun();

    private final Object lock = new Object();
    private volatile boolean started;
    private volatile boolean finished;
    private ExportConfig config;
    private RunWriter writer;
    private String startedAt;
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<String, AtomicInteger> occurrences = new ConcurrentHashMap<>();
    private final Map<String, MetricRef> metrics = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> agents = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> judges = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> datasets = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> scenariosWritten = new ConcurrentHashMap<>();
    private final Set<String> evaluatedKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> touchedSuites = ConcurrentHashMap.newKeySet();
    private final Set<String> touchedCases = ConcurrentHashMap.newKeySet();
    private final AtomicInteger evaluated = new AtomicInteger();
    private final AtomicInteger passed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger notEvaluated = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private final Map<Source, AtomicInteger> bySource = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> testCounts = new ConcurrentHashMap<>();
    private final AtomicLong costMicros = new AtomicLong();
    private volatile Pricing pricing = new Pricing();
    private final ThreadLocal<String> currentTrace = new ThreadLocal<>();
    private final ThreadLocal<EvalScenario> currentScenario = new ThreadLocal<>();
    private final ThreadLocal<String[]> currentTest = new ThreadLocal<>();
    private final ThreadLocal<MetricOverride> override = new ThreadLocal<>();

    private EvalRun() {}

    public static EvalRun get() {
        return INSTANCE;
    }

    /** Metric override installed by {@link EvalChecks}. */
    public record MetricOverride(MetricRef metric) {}

    // ---- lifecycle ------------------------------------------------------------------------

    /** Starts the bundle on first use; returns false when export is disabled or has failed. */
    private boolean ensureStarted() {
        if (finished) {
            return false;
        }
        if (started) {
            return writer != null && !writer.failed();
        }
        synchronized (lock) {
            if (started) {
                return writer != null && !writer.failed();
            }
            started = true;
            config = ExportConfig.fromSystem();
            if (!config.enabled()) {
                return false;
            }
            startedAt = Instant.now().toString();
            pricing = Pricing.fromSystem();
            writer = new RunWriter(config.root(), config.runId());
            writer.writeRun(runJson("RUNNING", null));
            Runtime.getRuntime().addShutdownHook(new Thread(this::finish, "eval4j-run-finish"));
            return !writer.failed();
        }
    }

    /** Whether evaluations are being exported. */
    public boolean isExporting() {
        return ensureStarted();
    }

    public String runId() {
        ensureStarted();
        return config == null ? null : config.runId();
    }

    /** Finishes the bundle. Idempotent; also runs from a JVM shutdown hook. */
    public void finish() {
        synchronized (lock) {
            if (!started || finished || writer == null || config == null || !config.enabled()) {
                finished = started;
                return;
            }
            finished = true;
            carryOver();
            String endedAt = Instant.now().toString();
            Map<String, Object> run = runJson("COMPLETE", endedAt);
            writer.writeRun(run);
            Map<String, Object> idx = new LinkedHashMap<>();
            idx.put("runId", config.runId());
            idx.put("startedAt", startedAt);
            idx.put("endedAt", endedAt);
            idx.put("status", "COMPLETE");
            idx.put("summary", run.get("summary"));
            writer.appendIndex(idx);
            Path runDir = writer.dir();
            Path root = config.root();
            for (RunExportListener l : ServiceLoader.load(RunExportListener.class)) {
                try {
                    l.runFinished(root, runDir);
                } catch (RuntimeException | LinkageError e) {
                    System.err.println("eval4j: run export listener failed: " + e);
                }
            }
        }
    }

    /**
     * Fills in cases this run did not evaluate from the latest earlier run (profile
     * BUILD/SAMPLE/FAST by default).
     */
    private void carryOver() {
        String flag = System.getProperty("eval4j.carryOver");
        boolean enabled =
                flag != null ? Boolean.parseBoolean(flag) : !"FULL".equals(config.profile());
        if (!enabled || writer.failed()) {
            return;
        }
        Set<String> cases = new java.util.HashSet<>(touchedCases);
        for (String k : scenariosWritten.keySet()) {
            cases.add(CaseKey.caseId(k));
        }
        Object branch = RunEnvironment.source().get("branch");
        int[] c =
                CarryOver.apply(
                        config.root(),
                        config.runId(),
                        branch == null ? null : branch.toString(),
                        cases,
                        new java.util.HashSet<>(touchedSuites),
                        evaluatedKeys,
                        writer,
                        seq.get());
        if (c[0] > 0) {
            bySource.computeIfAbsent(Source.CARRIED, k -> new AtomicInteger()).addAndGet(c[0]);
            evaluated.addAndGet(c[0]);
            passed.addAndGet(c[1]);
            failed.addAndGet(c[2]);
        }
    }

    /** Discards all state so a test can start a fresh run in the same JVM. */
    public static void resetForTests() {
        EvalRun r = INSTANCE;
        synchronized (r.lock) {
            r.started = false;
            r.finished = false;
            r.config = null;
            r.writer = null;
            r.seq.set(0);
            r.occurrences.clear();
            r.metrics.clear();
            r.agents.clear();
            r.judges.clear();
            r.datasets.clear();
            r.scenariosWritten.clear();
            r.scenarioByTest.clear();
            r.evaluatedKeys.clear();
            r.touchedCases.clear();
            r.touchedSuites.clear();
            r.evaluated.set(0);
            r.passed.set(0);
            r.failed.set(0);
            r.notEvaluated.set(0);
            r.errors.set(0);
            r.bySource.clear();
            r.testCounts.clear();
            r.costMicros.set(0);
            r.currentScenario.remove();
            r.currentTest.remove();
            r.override.remove();
            r.currentTrace.remove();
            r.traceCount.set(0);
            r.workflowTraceIds.clear();
            r.pricing = new Pricing();
        }
    }

    // ---- declarations ---------------------------------------------------------------------

    /** Declares the agent under test (shown on the Models page). Call from a test setup. */
    public void declareAgent(
            String id,
            String provider,
            String model,
            String promptId,
            String promptVersion,
            List<String> tools) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", id);
        putIfPresent(a, "provider", provider);
        putIfPresent(a, "model", model);
        putIfPresent(a, "promptId", promptId);
        putIfPresent(a, "promptVersion", promptVersion);
        if (tools != null) {
            a.put("tools", tools);
        }
        synchronized (lock) {
            agents.put(id, a);
        }
    }

    /** Declares a judge model and its settings. */
    public void declareJudge(
            String id,
            String provider,
            String model,
            Double temperature,
            Integer samples,
            String aggregation) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("id", id);
        putIfPresent(j, "provider", provider);
        putIfPresent(j, "model", model);
        putIfPresent(j, "temperature", temperature);
        putIfPresent(j, "samples", samples);
        putIfPresent(j, "aggregation", aggregation);
        synchronized (lock) {
            judges.put(id, j);
        }
    }

    /** Notes a judge seen at call time, if the test did not declare it. */
    public void noteJudge(String id, String label) {
        synchronized (lock) {
            judges.computeIfAbsent(
                    id,
                    k -> {
                        Map<String, Object> j = new LinkedHashMap<>();
                        j.put("id", id);
                        j.put("model", label);
                        return j;
                    });
        }
    }

    /** Declares a golden dataset: its scenarios are the cases the report expects results for. */
    public void declareDataset(String id, String name, String path, List<EvalScenario> scenarios) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", id);
        putIfPresent(d, "name", name);
        putIfPresent(d, "path", path);
        int n = scenarios == null ? 0 : scenarios.size();
        d.put("scenarioCount", n);
        StringBuilder sb = new StringBuilder();
        if (scenarios != null) {
            for (EvalScenario s : scenarios) {
                sb.append(CaseKey.of(s)).append('\n').append(s.input()).append('\n');
            }
        }
        d.put("hash", Hashes.sha256Hex(sb.toString()).substring(0, 32));
        synchronized (lock) {
            datasets.put(id, d);
        }
        if (scenarios != null && ensureStarted()) {
            for (EvalScenario s : scenarios) {
                writeScenario(s, id);
            }
        }
    }

    private void writeScenario(EvalScenario s, String datasetId) {
        String caseKey = CaseKey.of(s);
        scenariosWritten.computeIfAbsent(
                caseKey,
                k -> {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("id", k);
                    line.put("caseId", CaseKey.caseId(k));
                    line.put("datasetId", datasetId == null ? "default" : datasetId);
                    putIfPresent(line, "name", s.name());
                    putIfPresent(line, "input", s.input());
                    putIfPresent(line, "expectedOutput", s.expectedOutput());
                    putIfPresent(line, "expectedTools", s.expectedTools());
                    putIfPresent(line, "dimensions", s.dimensions());
                    putIfPresent(line, "tags", s.tags());
                    writer.append("scenarios.jsonl", line);
                    return line;
                });
    }

    // ---- current case ---------------------------------------------------------------------

    public void bindTest(String suite, String testName) {
        if (suite != null) {
            touchedSuites.add(suite);
        }
        currentTest.set(new String[] {suite, testName});
    }

    public void bindScenario(EvalScenario scenario) {
        currentScenario.set(scenario);
        String[] test = currentTest.get();
        if (scenario != null && test != null) {
            scenarioByTest.put(CaseKey.ofTest(test[0], test[1]), CaseKey.of(scenario));
        }
    }

    private final Map<String, String> scenarioByTest = new ConcurrentHashMap<>();

    public void unbind() {
        currentTest.remove();
        currentScenario.remove();
        currentTrace.remove();
    }

    void setOverride(MetricOverride o) {
        override.set(o);
    }

    void clearOverride() {
        override.remove();
    }

    public MetricOverride override() {
        return override.get();
    }

    // ---- profiles and budget ---------------------------------------------------------------

    /**
     * Called before a judge call that would be a cache miss. Under {@code FAST}, outside a {@code
     * SAMPLE} selection, or once the judge budget is spent, records a {@code NOT_EVALUATED}
     * evaluation and aborts the test (reported as aborted, never as passed). A no-op outside a
     * test.
     */
    public void gate(MetricRef metric, String judgeId) {
        if (!ensureStarted() || currentTest.get() == null) {
            return;
        }
        String reason = null;
        switch (config.profile()) {
            case "FAST" -> reason =
                    "Profile FAST does not call the judge, and no cached verdict exists.";
            case "SAMPLE" -> {
                if (!sampled(metric)) {
                    reason = "Not in this run's sample (profile SAMPLE).";
                }
            }
            default -> {}
        }
        String budget = System.getProperty("eval4j.judge.budgetUsd");
        if (reason == null && budget != null && !budget.isBlank()) {
            try {
                if (costMicros.get() / 1_000_000.0 >= Double.parseDouble(budget)) {
                    reason = "The judge budget of $" + budget + " is spent.";
                }
            } catch (NumberFormatException ignored) {
                // an unparsable budget is ignored rather than failing the build
            }
        }
        if (reason == null) {
            return;
        }
        record(Evaluation.builder(metric).status(EvalStatus.NOT_EVALUATED).reason(reason));
        throw new org.opentest4j.TestAbortedException("eval4j: " + reason);
    }

    private boolean sampled(MetricRef metric) {
        double rate = 0.2;
        long seed = 0;
        try {
            rate = Double.parseDouble(System.getProperty("eval4j.sample.rate", "0.2"));
            seed = Long.parseLong(System.getProperty("eval4j.sample.seed", "0"));
        } catch (NumberFormatException ignored) {
            // keep defaults
        }
        String[] test = currentTest.get();
        EvalScenario sc = currentScenario.get();
        String caseKey = sc != null ? CaseKey.of(sc) : CaseKey.ofTest(test[0], test[1]);
        String h = Hashes.sha256Hex(seed + "\u0000" + caseKey + "\u0000" + metric.id());
        double u = Long.parseUnsignedLong(h.substring(0, 15), 16) / (double) (1L << 60);
        return u < rate;
    }

    // ---- traces ----------------------------------------------------------------------------

    /** Records the trace of an agent run and links later evaluations of this case to it. */
    public void recordAgentResult(io.github.llm4j.agent.AgentResult result) {
        if (result == null || !ensureStarted() || currentTest.get() == null) {
            return;
        }
        EvalScenario sc = currentScenario.get();
        String[] test = currentTest.get();
        String caseKey = sc != null ? CaseKey.of(sc) : CaseKey.ofTest(test[0], test[1]);
        String traceId =
                "t_"
                        + Hashes.hex16(
                                "trace\u0000" + caseKey + "\u0000" + traceCount.incrementAndGet());
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("traceId", traceId);
        t.put("caseId", CaseKey.caseId(caseKey));
        t.put("type", "AGENT_STEPS");
        List<Map<String, Object>> steps = new ArrayList<>();
        int i = 1;
        for (io.github.llm4j.agent.AgentResult.AgentStep s : result.getSteps()) {
            if (i > 500) {
                break;
            }
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("index", i++);
            putIfPresent(st, "thought", cut(s.getThought()));
            st.put("action", s.getAction() == null ? "(none)" : s.getAction());
            putIfPresent(st, "input", cut(s.getActionInput()));
            putIfPresent(st, "observation", cut(s.getObservation()));
            st.put("outcome", s.getOutcome() == null ? "EXECUTED" : s.getOutcome().name());
            steps.add(st);
        }
        t.put("steps", steps);
        writer.append("traces.jsonl", t);
        currentTrace.set(traceId);
    }

    /**
     * Records a workflow trace (once per instance) and links later evaluations of this case to it.
     */
    public void recordWorkflowTrace(WorkflowTrace wt) {
        if (wt == null || !ensureStarted() || currentTest.get() == null) {
            return;
        }
        String existing = workflowTraceIds.get(wt);
        if (existing != null) {
            currentTrace.set(existing);
            return;
        }
        EvalScenario sc = currentScenario.get();
        String[] test = currentTest.get();
        String caseKey = sc != null ? CaseKey.of(sc) : CaseKey.ofTest(test[0], test[1]);
        String traceId =
                "t_"
                        + Hashes.hex16(
                                "trace\u0000" + caseKey + "\u0000" + traceCount.incrementAndGet());
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("traceId", traceId);
        t.put("caseId", CaseKey.caseId(caseKey));
        t.put("type", "WORKFLOW");
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("name", wt.name());
        if (!wt.nodes().isEmpty()) {
            Map<String, Object> g = new LinkedHashMap<>();
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (WorkflowTrace.Node n : wt.nodes()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", n.id());
                o.put("kind", n.kind());
                putIfPresent(o, "label", n.label());
                putIfPresent(o, "agent", n.agent());
                putIfPresent(o, "bound", n.bound());
                nodes.add(o);
            }
            List<Map<String, Object>> edges = new ArrayList<>();
            for (WorkflowTrace.Edge e : wt.edges()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("from", e.from());
                o.put("to", e.to());
                putIfPresent(o, "label", e.label());
                edges.add(o);
            }
            g.put("nodes", nodes);
            g.put("edges", edges);
            w.put("graph", g);
        }
        w.put("expectedPath", wt.expectedPath());
        w.put("actualPath", wt.actualPath());
        List<Map<String, Object>> events = new ArrayList<>();
        for (WorkflowTrace.Event e : wt.events()) {
            if (events.size() >= 20_000) {
                break;
            }
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("t", e.t());
            o.put("type", e.type());
            putIfPresent(o, "agent", e.agent());
            putIfPresent(o, "step", e.step());
            putIfPresent(o, "node", e.node());
            putIfPresent(o, "text", e.text());
            putIfPresent(o, "data", e.data());
            events.add(o);
        }
        w.put("events", events);
        List<Map<String, Object>> spend = new ArrayList<>();
        for (WorkflowTrace.SpendLine l : wt.spend()) {
            Map<String, Object> o = new LinkedHashMap<>();
            putIfPresent(o, "step", l.step());
            o.put("agent", l.agent() == null ? "(unknown)" : l.agent());
            putIfPresent(o, "model", l.model());
            o.put("promptTokens", l.promptTokens());
            o.put("completionTokens", l.completionTokens());
            o.put("calls", l.calls());
            putIfPresent(o, "costUsd", l.costUsd());
            o.put("estimated", l.estimated());
            spend.add(o);
        }
        w.put("spend", spend);
        putIfPresent(w, "budgetUsd", wt.budgetUsd());
        w.put("rewinds", wt.rewinds());
        putIfPresent(w, "rewindCap", wt.rewindCap());
        t.put("workflow", w);
        writer.append("traces.jsonl", t);
        workflowTraceIds.put(wt, traceId);
        currentTrace.set(traceId);
    }

    private final Map<WorkflowTrace, String> workflowTraceIds =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private final AtomicInteger traceCount = new AtomicInteger();

    private static String cut(String s) {
        return s == null || s.length() <= 5000 ? s : s.substring(0, 5000) + "…";
    }

    // ---- recording ------------------------------------------------------------------------

    /** Records one evaluation of the current case. A no-op when export is off. */
    public void record(Evaluation.Builder builder) {
        if (!ensureStarted()) {
            return;
        }
        MetricRef metric = builder.metric();
        String[] test = currentTest.get();
        EvalScenario scenario = currentScenario.get();
        String testId = test == null ? null : CaseKey.ofTest(test[0], test[1]);
        String caseKey;
        String scenarioId = null;
        if (scenario != null) {
            caseKey = CaseKey.of(scenario);
            scenarioId = caseKey;
            writeScenario(scenario, null);
        } else if (testId != null) {
            caseKey = testId;
        } else {
            caseKey = "(no test)";
        }
        int occurrence =
                occurrences
                        .computeIfAbsent(caseKey + "\u0000" + metric.id(), k -> new AtomicInteger())
                        .getAndIncrement();
        int n = seq.getAndIncrement();
        if (currentTrace.get() != null) {
            builder.traceId(currentTrace.get());
        }
        builder.priceWith(pricing, judges);
        Evaluation e =
                builder.build(
                        n,
                        CaseKey.key(caseKey, metric.id(), occurrence),
                        CaseKey.caseId(caseKey),
                        scenarioId,
                        testId,
                        Instant.now().toString());
        synchronized (lock) {
            metrics.putIfAbsent(metric.id(), metric);
        }
        touchedCases.add(e.caseId());
        if (e.status() == EvalStatus.EVALUATED) {
            evaluatedKeys.add(e.key());
        }
        count(e);
        writer.append("evaluations.jsonl", e);
    }

    private void count(Evaluation e) {
        bySource.computeIfAbsent(
                        e.source() == null ? Source.FRESH : e.source(), k -> new AtomicInteger())
                .incrementAndGet();
        switch (e.status()) {
            case EVALUATED -> {
                evaluated.incrementAndGet();
                if (Boolean.TRUE.equals(e.passed())) {
                    passed.incrementAndGet();
                } else {
                    failed.incrementAndGet();
                }
            }
            case NOT_EVALUATED -> notEvaluated.incrementAndGet();
            case ERROR -> errors.incrementAndGet();
        }
        if (e.costUsd() != null) {
            costMicros.addAndGet(Math.round(e.costUsd() * 1_000_000));
        }
    }

    /** Records how one test ended ({@code PASSED}, {@code FAILED}, {@code ABORTED}). */
    public void recordTest(
            String suite, String testName, String status, long durationMs, String message) {
        if (!ensureStarted()) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        String testId = CaseKey.ofTest(suite, testName);
        line.put("testId", testId);
        line.put("suite", suite);
        line.put("name", testName);
        String scenarioKey = scenarioByTest.get(testId);
        line.put("caseId", CaseKey.caseId(scenarioKey != null ? scenarioKey : testId));
        putIfPresent(line, "scenarioId", scenarioKey);
        line.put("status", status);
        line.put("durationMs", durationMs);
        putIfPresent(line, "message", message);
        testCounts.computeIfAbsent(status, k -> new AtomicInteger()).incrementAndGet();
        writer.append("tests.jsonl", line);
    }

    /** Appends a trace line (see the trace schema). */
    public void recordTrace(Map<String, Object> trace) {
        if (ensureStarted()) {
            writer.append("traces.jsonl", trace);
        }
    }

    /** Appends an optimization line (see the optimization schema). */
    public void recordOptimization(Map<String, Object> optimization) {
        if (ensureStarted()) {
            writer.append("optimizations.jsonl", optimization);
        }
    }

    // ---- run.json -------------------------------------------------------------------------

    private Map<String, Object> runJson(String status, String endedAt) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("schemaVersion", 1);
        run.put("format", "eval4j-run");
        run.put("runId", config.runId());
        putIfPresent(run, "groupId", config.groupId());
        run.put("status", status);
        run.put("startedAt", startedAt);
        if (endedAt != null) {
            run.put("endedAt", endedAt);
            run.put(
                    "durationMs",
                    Instant.parse(endedAt).toEpochMilli()
                            - Instant.parse(startedAt).toEpochMilli());
        }
        Map<String, Object> project = new LinkedHashMap<>();
        project.put(
                "name",
                Path.of("").toAbsolutePath().getFileName() == null
                        ? "project"
                        : Path.of("").toAbsolutePath().getFileName().toString());
        run.put("project", project);
        run.put("source", RunEnvironment.source());
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", config.profile());
        run.put("profile", profile);
        Map<String, Object> env = new LinkedHashMap<>(RunEnvironment.runtime());
        synchronized (lock) {
            env.put("agents", new ArrayList<>(agents.values()));
            env.put("judges", judgesWithStats());
            env.put("datasets", new ArrayList<>(datasets.values()));
            env.put("configHash", Hashes.hex16(config.profile()));
            List<Map<String, Object>> ms = new ArrayList<>();
            for (MetricRef m : metrics.values()) {
                ms.add(metricJson(m));
            }
            run.put("env", env);
            run.put("metrics", ms);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("evaluations", evaluated.get() + notEvaluated.get() + errors.get());
        summary.put("passed", passed.get());
        summary.put("failed", failed.get());
        summary.put("notEvaluated", notEvaluated.get());
        summary.put("errors", errors.get());
        Map<String, Object> src = new LinkedHashMap<>();
        for (Source s : Source.values()) {
            AtomicInteger c = bySource.get(s);
            if (c != null) {
                src.put(s.name(), c.get());
            }
        }
        summary.put("bySource", src);
        if (costMicros.get() > 0) {
            summary.put("costUsd", costMicros.get() / 1_000_000.0);
        }
        Map<String, Object> tests = new LinkedHashMap<>();
        testCounts.forEach((k, v) -> tests.put(k.toLowerCase(java.util.Locale.ROOT), v.get()));
        summary.put("tests", tests);
        run.put("summary", summary);
        return run;
    }

    private List<Map<String, Object>> judgesWithStats() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> j : judges.values()) {
            Map<String, Object> copy = new LinkedHashMap<>(j);
            JudgeStats.Snapshot s = JudgeStats.snapshot(String.valueOf(j.get("id")));
            if (s != null) {
                copy.put("stats", s.toMap());
            }
            out.add(copy);
        }
        return out;
    }

    private static Map<String, Object> metricJson(MetricRef m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", m.id());
        o.put("name", m.name());
        o.put("kind", m.kind().name());
        o.put("family", m.family());
        o.put("facet", m.facet());
        if (m.dimension() != null) {
            o.put("dimension", m.dimension());
        }
        putIfPresent(o, "threshold", m.threshold());
        putIfPresent(o, "unit", m.unit());
        putIfPresent(o, "budget", m.budget());
        return o;
    }

    private static void putIfPresent(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
