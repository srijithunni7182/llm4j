package io.github.llm4j.loom.inspect.state;

import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the console knows about one run, built only from OpenTelemetry-shaped records: log records say a step started or the run's
 * state changed, finished spans carry what a step used. The same code serves the embedded page and the inspect server, so the two
 * cannot drift apart.
 *
 * <p>It is safe to feed from one thread while others read: every method is synchronized, and {@link #view()} returns a copy.
 * Feeding is idempotent where it can be: a span is counted once however often it arrives, and a log record that repeats or goes
 * backwards in {@code loom.seq} (per process instance) is ignored; a jump forward is counted as a gap.
 */
public final class RunState {

    public enum Status { RUNNING, SLEEPING, PAUSED, WAITING, FINISHED, FAILED, STOPPED }

    private static final int RECENT = 200;
    private static final int SERIES = 60;

    /** A step that has started and not yet ended. */
    private record Open(String spanId, String name, String kind, String agent, String tool, String step, long startNanos) { }

    private static final class Agent {
        long tokens;
        long calls;
        BigDecimal cost = BigDecimal.ZERO;
        long steps;
    }

    private static final class Tool {
        long calls;
        long errors;
        long totalNanos;
        long successes;
        long softFailures;
        final Map<String, Long> failures = new java.util.TreeMap<>();
        final Percentiles latency = new Percentiles();
        ToolTally tally;
    }

    /** One model's calls, as the chat spans report them. */
    private static final class ModelStat {
        long calls;
        long prompt;
        long completion;
        long retries;
        long failovers;
        long truncated;
        long waited;
        final Map<String, Long> failures = new java.util.TreeMap<>();
        final Percentiles latency = new Percentiles();
    }

    /** What an agent's context looks like now, and how it got there. */
    private static final class ContextStat {
        long used;
        Long window;
        Long usable;
        Double fraction;
        String source;
        long compactions;
        long compactionAttempts;
        Map<String, Object> last;
        final ArrayDeque<Double> series = new ArrayDeque<>();
        long calls;
        long lastNanos;
        /** The last {@link #POINTS} model calls: call number, tokens used, and the fraction the process reported (null when it did not know the window). */
        final ArrayDeque<Object[]> points = new ArrayDeque<>();
        /** The last {@link #EVENTS} compactions, each with the call number it followed. */
        final ArrayDeque<Map<String, Object>> events = new ArrayDeque<>();
    }

    private static final int POINTS = 120;
    private static final int EVENTS = 60;

    /** What an agent's window is, from the process or, failing that, from the server's catalog. */
    private record Window(Long window, Long usable, Double fraction, String source) { }

    private Window windowOf(String agent, ContextStat c) {
        if (c.window != null || c.usable != null || c.fraction != null) return new Window(c.window, c.usable, c.fraction, "process");
        Long fromCatalog = windows.usable(models.get(agent));
        if (fromCatalog == null) return new Window(null, null, null, null);
        return new Window(null, fromCatalog, c.used / (double) fromCatalog, "catalog");
    }

    private final String client;
    private final String traceId;
    private String runId;
    private String workflow;
    private Status status = Status.RUNNING;
    private long startedNanos;
    private long endedNanos;
    private long lastEventNanos;
    private long tokens;
    private long calls;
    private BigDecimal cost = BigDecimal.ZERO;
    /** Cumulative spend after each finished agent step: [millis since start, tokens, cost]. Thinned past 600 points. */
    private final List<Object[]> spend = new ArrayList<>();
    private final Incidents incidents = new Incidents();
    private final Detectors detectors = new Detectors(incidents, Thresholds.defaults());
    private final SecurityState security = new SecurityState(incidents, detectors::thresholdsFor);
    private java.util.function.Supplier<List<Map<String, Object>>> securityProfile;
    private String controlPending;
    private String controlBy;
    private boolean heldByOperator;
    private Map<String, InspectRules> processRules = Map.of();
    private Map<String, InspectRules> centralRules = Map.of();
    private Map<String, String> models = Map.of();
    private PriceBook prices = PriceBook.EMPTY;
    private WindowBook windows = WindowBook.EMPTY;
    private Map<String, Object> graph;
    private java.util.function.Supplier<List<Map<String, Object>>> ladders;
    private String suspendReason;
    private String resumeAt;
    private String sleepWakeAt;
    private long gaps;
    private long duplicates;
    private final Map<String, Open> open = new LinkedHashMap<>();
    private final Map<String, Agent> agents = new LinkedHashMap<>();
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, Tool> tasks = new LinkedHashMap<>();
    private final Map<String, ModelStat> modelStats = new LinkedHashMap<>();
    private final Map<String, ContextStat> contexts = new LinkedHashMap<>();
    /** Where each statement of the script stands, by {@link StepPaths#normalize normalized} step id: state and how often it ran. */
    private final Map<String, Object[]> steps = new LinkedHashMap<>();
    private final Set<String> finished = new HashSet<>();
    private final Map<String, Long> lastSeq = new HashMap<>();
    private final ArrayDeque<Map<String, Object>> recent = new ArrayDeque<>();

    public RunState(String client, String traceId) {
        this.client = client;
        this.traceId = traceId;
    }

    public String client() {
        return client;
    }

    public String traceId() {
        return traceId;
    }

    /** The script's workflows as a graph, each node carrying the step path it runs under; null until a producer sets it. */
    public synchronized Map<String, Object> graph() {
        return graph;
    }

    /**
     * What each agent can reach, from the static audit (one map per agent: {@code agent} and the tools of each leg, {@code untrusted}, {@code private}, {@code outward});
     * asked for whenever the view is built, so the page can show what an agent could do next to what it did. Without it nothing is guessed.
     */
    public synchronized void setSecurityProfile(java.util.function.Supplier<List<Map<String, Object>>> profile) {
        this.securityProfile = profile;
    }

    /** Where the run's decisions stand on their autonomy ladders; asked for whenever the view is built. Embedded mode only. */
    public synchronized void setLadders(java.util.function.Supplier<List<Map<String, Object>>> ladders) {
        this.ladders = ladders;
    }

    /** The thresholds the incident rules use (the defaults until a script's {@code inspect} blocks say otherwise). */
    public synchronized void setThresholds(Thresholds thresholds) {
        detectors.thresholds(thresholds);
    }

    /** The per-agent rules a script's {@code inspect} blocks give, by agent name. */
    public synchronized void setRules(Map<String, InspectRules> rules) {
        processRules = rules == null ? Map.of() : Map.copyOf(rules);
        detectors.rules(merged());
    }

    /** Rules kept at a server, laid over the process's own rule by rule. */
    public synchronized void setCentralRules(Map<String, InspectRules> rules) {
        centralRules = rules == null ? Map.of() : Map.copyOf(rules);
        detectors.rules(merged());
    }

    private Map<String, InspectRules> merged() {
        if (centralRules.isEmpty()) return processRules;
        Map<String, InspectRules> out = new java.util.LinkedHashMap<>(processRules);
        centralRules.forEach((agent, c) -> out.merge(agent, c, RunState::over));
        return out;
    }

    /** {@code top}'s rules where it sets one, else {@code base}'s. */
    private static InspectRules over(InspectRules base, InspectRules top) {
        return new InspectRules(
                top.sameAction() != null ? top.sameAction() : base.sameAction(),
                top.sameOutput() != null ? top.sameOutput() : base.sameOutput(),
                top.toolFailures() != null ? top.toolFailures() : base.toolFailures(),
                top.ungroundedWarn() != null ? top.ungroundedWarn() : base.ungroundedWarn(),
                top.ungroundedHigh() != null ? top.ungroundedHigh() : base.ungroundedHigh(),
                top.pausedLongerThanSeconds() != null ? top.pausedLongerThanSeconds() : base.pausedLongerThanSeconds(),
                top.noProgressRounds() != null ? top.noProgressRounds() : base.noProgressRounds(),
                top.noProgressVariable() != null ? top.noProgressVariable() : base.noProgressVariable(),
                top.telemetry() == null ? base.telemetry() : top.telemetry().over(base.telemetry()),
                top.security() == null ? base.security() : top.security().over(base.security()));
    }

    /** The agents this run has shown or declared, and the model each declared agent uses. */
    public synchronized java.util.Set<String> agentNames() {
        java.util.Set<String> out = new java.util.TreeSet<>(agents.keySet());
        out.addAll(models.keySet());
        return out;
    }

    public synchronized Map<String, String> models() {
        return models;
    }

    public synchronized void setModels(Map<String, String> models) {
        this.models = models == null ? Map.of() : Map.copyOf(models);
    }

    /**
     * Context windows kept at the server: an agent whose process did not report a window gets one from here, by its model, from now on and in what it already showed.
     * What a process reports wins.
     */
    public synchronized void setWindows(WindowBook windows) {
        this.windows = windows == null ? WindowBook.EMPTY : windows;
        for (Map.Entry<String, ContextStat> e : contexts.entrySet()) {
            ContextStat c = e.getValue();
            if (c.calls == 0) continue;
            Window w = windowOf(e.getKey(), c);
            detectors.onContext(e.getKey(), w.fraction(), c.used, w.usable(), c.compactions, c.lastNanos);
        }
    }

    public synchronized WindowBook windows() {
        return windows;
    }

    public synchronized void setPrices(PriceBook prices) {
        this.prices = prices == null ? PriceBook.EMPTY : prices;
    }

    public synchronized void setGraph(Map<String, Object> graph) {
        this.graph = graph;
    }

    public synchronized String runId() {
        return runId;
    }

    public synchronized long lastEventNanos() {
        return lastEventNanos;
    }

    public synchronized Status status() {
        return status;
    }

    // ------------------------------------------------------------------------------ feeding

    /** Applies a log record from the process instance {@code instance}. */
    public synchronized void apply(String instance, LogRec r) {
        long seq = r.seq();
        if (seq >= 0 && instance != null) {
            Long last = lastSeq.get(instance);
            if (last != null) {
                if (seq <= last) {
                    duplicates++;
                    return;
                }
                if (seq > last + 1) gaps += seq - last - 1;
            }
            lastSeq.put(instance, seq);
        }
        touch(r.timeNanos());
        switch (r.eventName()) {
            case Semconv.EV_RUN_START -> runStart(r);
            case Semconv.EV_RUN_END -> runEnd(r);
            case Semconv.EV_RUN_SUSPENDED -> runSuspended(r);
            case Semconv.EV_SPAN_START -> spanStart(r);
            case Semconv.EV_INSPECT_SAMPLE -> detectors.onSample(r.text(Semconv.LOOM_INSPECT_AGENT), r.text(Semconv.LOOM_INSPECT_VAR), r.text(Semconv.LOOM_INSPECT_LOOP), r.text(Semconv.LOOM_INSPECT_HASH), r.timeNanos());
            case Semconv.EV_CONTROL -> control(r);
            case Semconv.EV_CONTEXT -> contextSample(r);
            case Semconv.EV_COMPACTION -> compactionSeen(r);
            case Semconv.EV_FAILURE -> failureSeen(r);
            case Semconv.EV_GUARD -> security.onGuard(r.text("loom.data.event"));
            case Semconv.EV_BUDGET -> detectors.onBudget(String.valueOf(r.text("loom.data.budget")), r.text("loom.data.kind"), r.body(), r.timeNanos());
            default -> { }
        }
        if (!Semconv.EV_CONTEXT.equals(r.eventName())) remember(r); // one per model call: the feed would be all context lines
    }

    /** Applies a finished span. */
    public synchronized void apply(SpanRec s) {
        if (!finished.add(s.spanId())) return; // already counted
        touch(s.endNanos());
        Open was = open.remove(s.spanId());
        String kind = s.text(Semconv.LOOM_SPAN_KIND);
        if (kind == null) return;
        if (Semconv.KIND_MODEL.equals(kind)) { // a model call belongs to its agent's step: it adds to the statistics, not to the steps or the feed
            modelEnded(s);
            return;
        }
        rememberEnd(s, kind);
        stepState(s.text(Semconv.LOOM_STEP), kind, s.statusCode() == SpanRec.STATUS_ERROR ? "failed" : "done");
        switch (kind) {
            case Semconv.KIND_AGENT -> {
                agentEnded(s);
                detectors.onAgentEnd(s.text(Semconv.GEN_AI_AGENT_NAME), s.text(Semconv.LOOM_STEP), s.text(Semconv.LOOM_OUTPUT_HASH),
                        number(s.attr(Semconv.LOOM_GROUNDING_FIGURES)), number(s.attr(Semconv.LOOM_GROUNDING_UNGROUNDED)), s.text(Semconv.LOOM_GROUNDING_EXAMPLES), s.endNanos());
            }
            case Semconv.KIND_TOOL -> {
                toolEnded(s, was);
                // a call the runtime blocked as a repeat says nothing about whether the tool works: neither a failure nor a success
                if (!Boolean.TRUE.equals(s.attr(Semconv.LOOM_TOOL_NO_RESULT))) detectors.onToolEnd(s.text(Semconv.GEN_AI_AGENT_NAME), s.text(Semconv.GEN_AI_TOOL_NAME), s.statusCode() == SpanRec.STATUS_ERROR, s.text(Semconv.LOOM_STEP), s.endNanos());
            }
            case Semconv.KIND_TASK -> taskEnded(s);
            case Semconv.KIND_SLEEP -> {
                sleepWakeAt = null;
                if (status == Status.SLEEPING) status = Status.RUNNING;
            }
            default -> { }
        }
    }

    private void runStart(LogRec r) {
        if (r.text(Semconv.LOOM_RUN_ID) != null) runId = r.text(Semconv.LOOM_RUN_ID);
        if (r.text(Semconv.LOOM_WORKFLOW) != null) workflow = r.text(Semconv.LOOM_WORKFLOW);
        if (startedNanos == 0) startedNanos = r.timeNanos();
        endedNanos = 0;
        status = Status.RUNNING;
        suspendReason = null;
        resumeAt = null;
        sleepWakeAt = null;
        open.clear(); // whatever the earlier process had open ended with it
        controlPending = null;
        heldByOperator = false;
        detectors.onResumed(r.timeNanos());
    }

    private void runEnd(LogRec r) {
        String outcome = String.valueOf(r.text(Semconv.LOOM_RUN_STATUS));
        switch (outcome) {
            case Semconv.STATUS_COMPLETED -> status = Status.FINISHED;
            case Semconv.STATUS_FAILED -> status = Status.FAILED;
            case Semconv.STATUS_STOPPED, Semconv.STATUS_BUDGET -> status = Status.STOPPED;
            case Semconv.STATUS_SUSPENDED -> {
                if (status != Status.SLEEPING && status != Status.WAITING) status = Status.PAUSED;
            }
            default -> { }
        }
        if (status == Status.FINISHED || status == Status.FAILED || status == Status.STOPPED) {
            endedNanos = r.timeNanos();
            controlPending = null; // nothing can be asked of a run that has ended
            heldByOperator = false;
            detectors.onRunEnd(r.timeNanos());
            security.onRunEnd(r.timeNanos());
            if (status == Status.FAILED && r.text(Semconv.LOOM_RUN_FAILED_NAME) != null) {
                detectors.onBroken(r.text(Semconv.LOOM_RUN_FAILED_KIND), r.text(Semconv.LOOM_RUN_FAILED_NAME), r.text(Semconv.LOOM_RUN_FAILED_STEP),
                        r.text(Semconv.LOOM_RUN_FAILED_CATEGORY), r.timeNanos());
            }
        }
        open.clear();
        controlPending = null;
    }

    /** A pause, resume, suspend or stop an operator asked for: queued until the run reaches a step, then in effect. Not an incident: someone meant it. */
    private void control(LogRec r) {
        String action = r.text(Semconv.LOOM_CONTROL_ACTION);
        String state = r.text(Semconv.LOOM_CONTROL_STATE);
        String by = r.text(Semconv.LOOM_CONTROL_BY);
        if (action == null || state == null) return;
        switch (state) {
            case "requested" -> {
                controlPending = action;
                controlBy = by;
            }
            case "held" -> {
                heldByOperator = true;
                controlPending = null;
                status = Status.PAUSED;
                suspendReason = "OPERATOR";
            }
            case "resumed", "cancelled" -> {
                if (action.equals(controlPending) || "pause".equals(action)) controlPending = null;
                if (heldByOperator) {
                    heldByOperator = false;
                    if (status == Status.PAUSED && "OPERATOR".equals(suspendReason)) {
                        status = Status.RUNNING;
                        suspendReason = null;
                    }
                }
            }
            case "applied" -> controlPending = null; // the run ends next (stopped, or suspended by the operator)
            default -> { }
        }
    }

    private void runSuspended(LogRec r) {
        suspendReason = r.text(Semconv.LOOM_SUSPEND_REASON);
        resumeAt = r.text(Semconv.LOOM_RESUME_AT);
        if ("SLEEP".equals(suspendReason)) {
            status = Status.SLEEPING;
            sleepWakeAt = resumeAt;
        } else if ("HUMAN".equals(suspendReason)) {
            status = Status.WAITING;
        } else if ("OPERATOR".equals(suspendReason)) {
            status = Status.PAUSED; // suspended by a person: shown as paused, and not an incident
        } else {
            status = Status.PAUSED;
            detectors.onPaused(suspendReason, resumeAt, r.timeNanos());
        }
    }

    private void spanStart(LogRec r) {
        String id = r.spanId();
        if (id == null || finished.contains(id)) return;
        String kind = String.valueOf(r.text(Semconv.LOOM_SPAN_KIND));
        open.put(id, new Open(id, r.body(), kind, r.text(Semconv.GEN_AI_AGENT_NAME), r.text(Semconv.GEN_AI_TOOL_NAME),
                r.text(Semconv.LOOM_STEP), r.timeNanos()));
        stepState(r.text(Semconv.LOOM_STEP), kind, "running");
        if (Semconv.KIND_SLEEP.equals(kind)) {
            sleepWakeAt = r.text(Semconv.LOOM_SLEEP_WAKE_AT);
            status = Status.SLEEPING;
        } else if (status == Status.SLEEPING || status == Status.PAUSED || status == Status.WAITING) {
            if (status == Status.PAUSED) detectors.onResumed(r.timeNanos());
            status = Status.RUNNING; // something started, so the run is going again
        }
        if (Semconv.KIND_TOOL.equals(kind)) {
            detectors.onToolStart(r.text(Semconv.GEN_AI_AGENT_NAME), r.text(Semconv.LOOM_STEP), r.text(Semconv.GEN_AI_TOOL_NAME), r.text(Semconv.LOOM_ACTION_HASH), r.timeNanos());
        }
    }

    private void agentEnded(SpanRec s) {
        String name = s.text(Semconv.GEN_AI_AGENT_NAME);
        if (name == null) return;
        Agent a = agents.computeIfAbsent(name, k -> new Agent());
        long t = number(s.attr(Semconv.LOOM_USAGE_TOTAL_TOKENS));
        long c = number(s.attr(Semconv.LOOM_USAGE_CALLS));
        BigDecimal usd = decimal(s.attr(Semconv.LOOM_COST_USD));
        if (s.attr(Semconv.LOOM_COST_USD) == null && !prices.isEmpty()) {
            // the process has no price file: cost the step here, from the tokens it reported and the model its agent uses
            BigDecimal priced = prices.cost(models.get(name), number(s.attr(Semconv.GEN_AI_USAGE_INPUT_TOKENS)), number(s.attr(Semconv.GEN_AI_USAGE_OUTPUT_TOKENS)));
            if (priced != null) usd = priced;
        }
        a.tokens += t;
        a.calls += c;
        a.cost = a.cost.add(usd);
        a.steps++;
        tokens += t;
        calls += c;
        cost = cost.add(usd);
        spend.add(new Object[] {startedNanos > 0 ? Math.max(0, (s.endNanos() - startedNanos) / 1_000_000L) : 0L, tokens, cost.toPlainString()});
        if (spend.size() > 600) {
            for (int i = spend.size() - 2; i >= 1; i -= 2) spend.remove(i);
        }
    }

    private void toolEnded(SpanRec s, Open was) {
        String name = s.text(Semconv.GEN_AI_TOOL_NAME);
        if (name == null) return;
        security.onTool(s.text(Semconv.GEN_AI_AGENT_NAME), name, s.text(Semconv.LOOM_TOOL_REACH), !Boolean.FALSE.equals(s.attr(Semconv.LOOM_TOOL_REACH_KNOWN)),
                s.text(Semconv.LOOM_INJECTION_PATTERNS), number(s.attr(Semconv.LOOM_INJECTION_COUNT)), s.text(Semconv.ERROR_TYPE),
                Boolean.TRUE.equals(s.attr(Semconv.LOOM_TOOL_NO_RESULT)), s.text(Semconv.LOOM_STEP), s.endNanos());
        Tool t = tools.computeIfAbsent(name, k -> new Tool());
        if (t.tally == null) t.tally = new ToolTally(name);
        t.calls++;
        long nanos = Math.max(0, s.endNanos() - s.startNanos());
        t.totalNanos += nanos;
        boolean noResult = Boolean.TRUE.equals(s.attr(Semconv.LOOM_TOOL_NO_RESULT));
        if (s.statusCode() == SpanRec.STATUS_ERROR) {
            t.errors++;
            String category = s.text(Semconv.ERROR_TYPE);
            t.failures.merge(category == null ? "other" : category, 1L, Long::sum);
            if (Boolean.TRUE.equals(s.attr(Semconv.LOOM_TOOL_SOFT_FAILURE))) t.softFailures++;
        } else if (!noResult) {
            t.successes++;
        }
        if (!noResult) {
            long millis = s.attr(Semconv.LOOM_TOOL_DURATION_MS) != null ? number(s.attr(Semconv.LOOM_TOOL_DURATION_MS)) : nanos / 1_000_000L;
            boolean failed = s.statusCode() == SpanRec.STATUS_ERROR;
            detectors.onToolCall(s.text(Semconv.GEN_AI_AGENT_NAME), name, !failed, s.text(Semconv.ERROR_TYPE), millis, t.latency.p50(), t.latency.count(), s.endNanos());
            t.latency.add(millis);
            t.tally.record(s.text(Semconv.GEN_AI_AGENT_NAME), !failed, s.text(Semconv.ERROR_TYPE), OtlpJson.instant(s.endNanos()).toString(), millis,
                    Boolean.TRUE.equals(s.attr(Semconv.LOOM_TOOL_SOFT_FAILURE)));
        } else {
            t.tally.skipped();
        }
    }

    /** A task finished: it counts like a tool call (a replayed or simulated one ran nowhere, so it counts for nothing). */
    private void taskEnded(SpanRec s) {
        String name = s.text(Semconv.LOOM_TASK);
        if (name == null || Boolean.TRUE.equals(s.attr("loom.replayed")) || "simulated".equals(s.text(Semconv.LOOM_TASK_OUTCOME))) return;
        Tool t = tasks.computeIfAbsent(name, k -> new Tool());
        if (t.tally == null) t.tally = new ToolTally(name, "task");
        boolean failed = s.statusCode() == SpanRec.STATUS_ERROR;
        long millis = Math.max(0, s.endNanos() - s.startNanos()) / 1_000_000L;
        t.calls++;
        t.totalNanos += Math.max(0, s.endNanos() - s.startNanos());
        if (failed) {
            t.errors++;
            String category = s.text(Semconv.ERROR_TYPE);
            t.failures.merge(category == null ? "other" : category, 1L, Long::sum);
        } else {
            t.successes++;
        }
        t.latency.add(millis);
        t.tally.record(null, !failed, s.text(Semconv.ERROR_TYPE), OtlpJson.instant(s.endNanos()).toString(), millis, false);
    }

    /** A task or delegate failed. If its {@code on_failure} took over, that is an incident; if not, the run's end says so. */
    private void failureSeen(LogRec r) {
        if (!Boolean.TRUE.equals(r.attr(Semconv.LOOM_FAILURE_HANDLED))) return;
        detectors.onDiverted(r.text(Semconv.LOOM_FAILURE_KIND), r.text(Semconv.LOOM_FAILURE_NAME), r.text(Semconv.LOOM_STEP), r.text(Semconv.LOOM_FAILURE_CATEGORY),
                number(r.attr(Semconv.LOOM_FAILURE_ATTEMPTS)), r.timeNanos());
    }

    private void modelEnded(SpanRec s) {
        String model = s.text(Semconv.GEN_AI_RESPONSE_MODEL);
        if (model == null) model = s.text(Semconv.GEN_AI_REQUEST_MODEL);
        ModelStat m = modelStats.computeIfAbsent(model == null ? "unknown" : model, k -> new ModelStat());
        m.calls++;
        m.prompt += number(s.attr(Semconv.GEN_AI_USAGE_INPUT_TOKENS));
        m.completion += number(s.attr(Semconv.GEN_AI_USAGE_OUTPUT_TOKENS));
        long attempts = s.attr(Semconv.LOOM_MODEL_ATTEMPTS) == null ? 1 : number(s.attr(Semconv.LOOM_MODEL_ATTEMPTS));
        m.retries += Math.max(0, attempts - 1);
        m.waited += number(s.attr(Semconv.LOOM_MODEL_WAITED_MS));
        String failovers = s.text(Semconv.LOOM_MODEL_FAILOVERS);
        if (failovers != null && !failovers.isBlank()) m.failovers += failovers.split(",").length;
        if (Boolean.TRUE.equals(s.attr(Semconv.LOOM_MODEL_TRUNCATED))) m.truncated++;
        if (s.statusCode() == SpanRec.STATUS_ERROR) {
            String category = s.text(Semconv.ERROR_TYPE);
            m.failures.merge(category == null ? "other" : category, 1L, Long::sum);
        }
        long millis = s.attr(Semconv.LOOM_MODEL_DURATION_MS) != null ? number(s.attr(Semconv.LOOM_MODEL_DURATION_MS)) : Math.max(0, s.endNanos() - s.startNanos()) / 1_000_000L;
        String agent = s.text(Semconv.GEN_AI_AGENT_NAME);
        detectors.onModelCall(agent, s.text(Semconv.LOOM_STEP), Boolean.TRUE.equals(s.attr(Semconv.LOOM_MODEL_TRUNCATED)), number(s.attr(Semconv.GEN_AI_USAGE_OUTPUT_TOKENS)),
                s.text(Semconv.GEN_AI_RESPONSE_FINISH_REASONS), s.endNanos());
        if (s.statusCode() != SpanRec.STATUS_ERROR) detectors.onModelDuration(model == null ? "unknown" : model, agent, millis, m.latency.p50(), m.latency.count(), s.endNanos());
        m.latency.add(millis);
    }

    private void contextSample(LogRec r) {
        String agent = r.text(Semconv.GEN_AI_AGENT_NAME);
        if (agent == null) return;
        ContextStat c = contexts.computeIfAbsent(agent, k -> new ContextStat());
        c.used = number(r.attr(Semconv.LOOM_CONTEXT_USED));
        c.window = r.attr(Semconv.LOOM_CONTEXT_WINDOW) == null ? null : number(r.attr(Semconv.LOOM_CONTEXT_WINDOW));
        c.usable = r.attr(Semconv.LOOM_CONTEXT_USABLE) == null ? null : number(r.attr(Semconv.LOOM_CONTEXT_USABLE));
        c.fraction = r.attr(Semconv.LOOM_CONTEXT_FRACTION) instanceof Number f ? f.doubleValue() : null;
        c.source = r.text(Semconv.LOOM_CONTEXT_SOURCE);
        if (c.fraction != null) {
            c.series.addLast(c.fraction);
            while (c.series.size() > SERIES) c.series.removeFirst();
        }
        c.calls++;
        c.lastNanos = r.timeNanos();
        c.points.addLast(new Object[] {c.calls, c.used, c.fraction});
        while (c.points.size() > POINTS) c.points.removeFirst();
        Window w = windowOf(agent, c);
        detectors.onContext(agent, w.fraction(), c.used, w.usable(), c.compactions, r.timeNanos());
    }

    private void compactionSeen(LogRec r) {
        String agent = r.text(Semconv.GEN_AI_AGENT_NAME);
        if (agent == null) return;
        ContextStat c = contexts.computeIfAbsent(agent, k -> new ContextStat());
        c.compactionAttempts++;
        String outcome = r.text(Semconv.LOOM_COMPACTION_OUTCOME);
        if ("COMPACTED".equals(outcome)) c.compactions++;
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("strategy", r.text(Semconv.LOOM_COMPACTION_STRATEGY));
        last.put("outcome", outcome);
        last.put("before", number(r.attr(Semconv.LOOM_COMPACTION_BEFORE)));
        last.put("after", number(r.attr(Semconv.LOOM_COMPACTION_AFTER)));
        last.put("at", OtlpJson.instant(r.timeNanos()).toString());
        c.last = last;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("n", c.calls);
        event.put("strategy", last.get("strategy"));
        event.put("outcome", outcome);
        event.put("before", last.get("before"));
        event.put("after", last.get("after"));
        c.events.addLast(event);
        while (c.events.size() > EVENTS) c.events.removeFirst();
        detectors.onCompaction(agent, outcome, number(r.attr(Semconv.LOOM_COMPACTION_BEFORE)), number(r.attr(Semconv.LOOM_COMPACTION_AFTER)), r.timeNanos());
    }

    /** A tool call shares its step id with the agent that made it, so only agent, task and sleep spans speak for a step. */
    private void stepState(String step, String kind, String state) {
        if (step == null || Semconv.KIND_TOOL.equals(kind) || Semconv.KIND_WORKFLOW.equals(kind)) return;
        Object[] st = steps.computeIfAbsent(StepPaths.normalize(step), k -> new Object[] {"", 0});
        if ("running".equals(state)) st[1] = (Integer) st[1] + 1;
        st[0] = state;
    }

    private void touch(long nanos) {
        if (nanos > lastEventNanos) lastEventNanos = nanos;
    }

    private void remember(LogRec r) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", OtlpJson.instant(r.timeNanos()).toString());
        line.put("event", r.eventName());
        String agent = r.text(Semconv.GEN_AI_AGENT_NAME);
        if (agent != null) line.put("agent", agent);
        String text = r.body();
        String kind = r.text(Semconv.LOOM_SPAN_KIND);
        if (Semconv.EV_SPAN_START.equals(r.eventName()) && kind != null) {
            line.put("kind", kind);
            // a started step reads as what it is doing: the tool and its input, or the task given to the agent
            String input = r.text(Semconv.LOOM_TEXT);
            String tool = r.text(Semconv.GEN_AI_TOOL_NAME);
            if (tool != null) text = tool + (input != null ? " " + input : "");
            else if (input != null) text = input;
            if (Semconv.KIND_SLEEP.equals(kind)) {
                text = "sleeping";
                if (r.text(Semconv.LOOM_SLEEP_WAKE_AT) != null) line.put("until", r.text(Semconv.LOOM_SLEEP_WAKE_AT));
            }
        }
        line.put("text", text);
        recent.addLast(line);
        while (recent.size() > RECENT) recent.removeFirst();
    }

    /** A finished step goes in the latest-events feed too, with its result text when the detail level allows one. */
    private void rememberEnd(SpanRec s, String kind) {
        if (Semconv.KIND_WORKFLOW.equals(kind)) return;
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", OtlpJson.instant(s.endNanos()).toString());
        line.put("event", "loom." + kind + ".end");
        line.put("kind", kind);
        String agent = s.text(Semconv.GEN_AI_AGENT_NAME);
        if (agent != null) line.put("agent", agent);
        String text = s.text(Semconv.LOOM_TEXT);
        boolean failed = s.statusCode() == SpanRec.STATUS_ERROR;
        if (text == null && Semconv.KIND_SLEEP.equals(kind)) text = "woke up";
        line.put("text", text != null ? text : s.name() + (failed ? " failed" : ""));
        if (failed) line.put("failed", true);
        recent.addLast(line);
        while (recent.size() > RECENT) recent.removeFirst();
    }

    // ------------------------------------------------------------------------------ reading

    /** A copy of everything the console shows for this run, as plain maps and lists (ready for JSON). */
    public Map<String, Object> view() {
        return view(Instant.now());
    }

    /** As {@link #view()}, with {@code now} as the moment of looking: a run that has not ended has been going until now, even while it sleeps. */
    public synchronized Map<String, Object> view(Instant now) {
        detectors.refreshPaused(OtlpJson.nanos(now));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("client", client);
        v.put("traceId", traceId);
        v.put("runId", runId);
        v.put("workflow", workflow);
        v.put("status", status.name().toLowerCase(java.util.Locale.ROOT));
        if (startedNanos > 0) v.put("startedAt", OtlpJson.instant(startedNanos).toString());
        if (endedNanos > 0) v.put("endedAt", OtlpJson.instant(endedNanos).toString());
        if (lastEventNanos > 0) v.put("lastEventAt", OtlpJson.instant(lastEventNanos).toString());
        long until = endedNanos > 0 ? endedNanos : Math.max(lastEventNanos, OtlpJson.nanos(now));
        v.put("elapsedMillis", startedNanos > 0 && until >= startedNanos ? (until - startedNanos) / 1_000_000L : 0L);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("tokens", tokens);
        totals.put("calls", calls);
        totals.put("cost", cost.toPlainString());
        v.put("totals", totals);

        if (status == Status.SLEEPING && sleepWakeAt != null) {
            v.put("sleeping", Map.of("wakeAt", sleepWakeAt));
        }
        if (suspendReason != null && status != Status.RUNNING) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("reason", suspendReason);
            if (resumeAt != null) s.put("resumeAt", resumeAt);
            v.put("suspended", s);
        }

        List<Map<String, Object>> agentList = new ArrayList<>();
        Set<String> names = new java.util.LinkedHashSet<>(agents.keySet());
        for (Open o : open.values()) if (o.agent() != null) names.add(o.agent());
        names.addAll(contexts.keySet());
        for (String name : names) {
            Agent a = agents.getOrDefault(name, new Agent());
            List<String> activeTools = new ArrayList<>();
            String currentStep = null;
            boolean running = false;
            for (Open o : open.values()) {
                if (!name.equals(o.agent())) continue;
                if (Semconv.KIND_AGENT.equals(o.kind())) {
                    running = true;
                    currentStep = o.step();
                } else if (Semconv.KIND_TOOL.equals(o.kind()) && o.tool() != null) {
                    activeTools.add(o.tool());
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("status", running ? "running" : a.steps > 0 ? "done" : "idle");
            if (currentStep != null) m.put("currentStep", currentStep);
            m.put("tokens", a.tokens);
            m.put("calls", a.calls);
            m.put("cost", a.cost.toPlainString());
            m.put("activeTools", activeTools);
            ContextStat cs = contexts.get(name);
            if (cs != null) m.put("context", contextView(name, cs));
            agentList.add(m);
        }
        v.put("agents", agentList);

        List<Map<String, Object>> toolList = new ArrayList<>();
        Set<String> toolNames = new java.util.LinkedHashSet<>(tools.keySet());
        for (Open o : open.values()) if (Semconv.KIND_TOOL.equals(o.kind()) && o.tool() != null) toolNames.add(o.tool());
        for (String name : toolNames) {
            Tool t = tools.getOrDefault(name, new Tool());
            long inFlight = open.values().stream().filter(o -> Semconv.KIND_TOOL.equals(o.kind()) && name.equals(o.tool())).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("calls", t.calls);
            m.put("errors", t.errors);
            m.put("inFlight", inFlight);
            m.put("avgMillis", t.calls == 0 ? 0L : t.totalNanos / t.calls / 1_000_000L);
            m.put("successes", t.successes);
            long ran = t.successes + t.errors; // a call the runtime never ran says nothing about whether the tool works
            if (ran > 0) m.put("successRate", t.successes / (double) ran);
            m.put("softFailures", t.softFailures);
            m.put("failures", new LinkedHashMap<>(t.failures));
            m.put("p50Ms", t.latency.p50());
            m.put("p95Ms", t.latency.p95());
            if (t.tally != null) m.put("health", t.tally.snapshot(detectors.thresholdsFor(null)).toHealthMap());
            toolList.add(m);
        }
        v.put("tools", toolList);
        v.put("security", security.view(securityProfile == null ? List.of() : securityProfile.get()));
        List<Map<String, Object>> taskList = new ArrayList<>();
        tasks.forEach((name, t) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("calls", t.calls);
            m.put("errors", t.errors);
            m.put("successes", t.successes);
            if (t.calls > 0) m.put("successRate", t.successes / (double) t.calls);
            m.put("avgMillis", t.calls == 0 ? 0L : t.totalNanos / t.calls / 1_000_000L);
            m.put("failures", new LinkedHashMap<>(t.failures));
            m.put("p50Ms", t.latency.p50());
            m.put("p95Ms", t.latency.p95());
            m.put("health", t.tally.snapshot(detectors.thresholdsFor(null)).toHealthMap());
            taskList.add(m);
        });
        v.put("tasks", taskList);
        List<Map<String, Object>> modelList = new ArrayList<>();
        modelStats.forEach((name, ms) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("calls", ms.calls);
            m.put("failures", new LinkedHashMap<>(ms.failures));
            m.put("promptTokens", ms.prompt);
            m.put("completionTokens", ms.completion);
            m.put("retries", ms.retries);
            m.put("failovers", ms.failovers);
            m.put("truncated", ms.truncated);
            m.put("waitedMs", ms.waited);
            m.put("p50Ms", ms.latency.p50());
            m.put("p95Ms", ms.latency.p95());
            modelList.add(m);
        });
        v.put("models", modelList);

        List<Map<String, Object>> steps = new ArrayList<>();
        for (Open o : open.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("span", o.spanId());
            m.put("name", o.name());
            m.put("kind", o.kind());
            if (o.agent() != null) m.put("agent", o.agent());
            if (o.step() != null) m.put("step", o.step());
            m.put("since", OtlpJson.instant(o.startNanos()).toString());
            steps.add(m);
        }
        v.put("openSteps", steps);
        List<Map<String, Object>> rungs = List.of();
        if (ladders != null) {
            try {
                rungs = ladders.get();
            } catch (RuntimeException e) {
                rungs = List.of(); // a store that cannot be read must not take the page down
            }
        }
        v.put("ladders", rungs);
        Map<String, Object> stepStates = new LinkedHashMap<>();
        this.steps.forEach((k, st) -> stepStates.put(k, Map.of("state", st[0], "runs", st[1])));
        v.put("steps", stepStates);
        List<Map<String, Object>> series = new ArrayList<>();
        for (Object[] p : spend) series.add(Map.of("t", p[0], "tokens", p[1], "cost", p[2]));
        v.put("spend", series);

        v.put("gaps", gaps);
        v.put("incidents", incidents.view());
        Map<String, Object> ctl = new LinkedHashMap<>();
        ctl.put("held", heldByOperator);
        if (controlPending != null) ctl.put("pending", controlPending);
        if (controlBy != null && (controlPending != null || heldByOperator)) ctl.put("by", controlBy);
        v.put("control", ctl);
        v.put("duplicates", duplicates);
        v.put("recent", new ArrayList<>(recent));
        return v;
    }

    private static final java.util.Set<String> SECURITY_KINDS = java.util.Set.of("TRIFECTA", "INJECTION", "ACCESS");

    /** Open security incidents (trifecta, injection, access), as the incident view shows them. */
    private List<Map<String, Object>> openSecurityIncidents() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> i : incidents.view()) if ("open".equals(i.get("state")) && SECURITY_KINDS.contains(String.valueOf(i.get("kind")))) out.add(i);
        return out;
    }

    /** How many security incidents are open now: what a tab shows. */
    public synchronized int openSecurityCount() {
        return openSecurityIncidents().size();
    }

    /** The open security incidents of this run, each with the run it belongs to. For a server's security page. */
    public synchronized List<Map<String, Object>> securityIncidents() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> i : openSecurityIncidents()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("client", client);
            m.put("traceId", traceId);
            m.put("runId", runId);
            m.putAll(i);
            out.add(m);
        }
        return out;
    }

    /** The agents of this run whose security verdict matters (exercised, accepted or armed), each with the run it belongs to. */
    public synchronized List<Map<String, Object>> securityAgents() {
        List<Map<String, Object>> out = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) security.view(securityProfile == null ? List.of() : securityProfile.get()).get("agents");
        for (Map<String, Object> a : all) {
            String verdict = String.valueOf(a.get("verdict"));
            if (!verdict.equals("exercised") && !verdict.equals("accepted") && !verdict.equals("armed")) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("client", client);
            m.put("traceId", traceId);
            m.put("runId", runId);
            m.put("workflow", workflow);
            m.put("agent", a.get("agent"));
            m.put("verdict", verdict);
            if (a.get("acceptedReason") != null) m.put("acceptedReason", a.get("acceptedReason"));
            m.put("assumed", a.get("assumed"));
            out.add(m);
        }
        return out;
    }

    /** The security counts of this run, for a server to add up across runs. */
    public synchronized Map<String, Long> securityCounts() {
        return security.counts();
    }

    /** Every tool this run has seen, with its health (see {@link ToolSummary}); for a server to merge across runs. */
    public synchronized List<ToolSummary> toolSummaries() {
        List<ToolSummary> out = new ArrayList<>();
        Thresholds t = detectors.thresholdsFor(null);
        for (Tool tool : tools.values()) if (tool.tally != null) out.add(tool.tally.snapshot(t));
        for (Tool task : tasks.values()) if (task.tally != null) out.add(task.tally.snapshot(t));
        return out;
    }

    private Map<String, Object> contextView(String agent, ContextStat c) {
        Window w = windowOf(agent, c);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("used", c.used);
        if (w.window() != null) m.put("window", w.window());
        if (w.usable() != null) m.put("usable", w.usable());
        if (w.fraction() != null) m.put("fraction", w.fraction());
        if (w.source() != null) m.put("windowSource", w.source());
        if (c.source != null) m.put("source", c.source);
        m.put("compactions", c.compactions);
        m.put("attempts", c.compactionAttempts);
        if (c.last != null) m.put("lastCompaction", new LinkedHashMap<>(c.last));
        if (!c.series.isEmpty()) m.put("series", new ArrayList<>(c.series));
        List<Map<String, Object>> pts = new ArrayList<>();
        for (Object[] p : c.points) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("n", p[0]);
            pm.put("used", p[1]);
            Double reported = (Double) p[2];
            if (reported != null) pm.put("fraction", reported);
            else if (w.source() != null && w.usable() != null) pm.put("fraction", ((Number) p[1]).longValue() / (double) w.usable());
            pts.add(pm);
        }
        m.put("points", pts);
        List<Map<String, Object>> evs = new ArrayList<>();
        for (Map<String, Object> e : c.events) evs.add(new LinkedHashMap<>(e));
        m.put("events", evs);
        return m;
    }

    /** A short view for a list of runs: who, what, how it is going, and the totals. */
    public synchronized Map<String, Object> summary() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("client", client);
        v.put("traceId", traceId);
        v.put("runId", runId);
        v.put("workflow", workflow);
        v.put("status", status.name().toLowerCase(java.util.Locale.ROOT));
        if (startedNanos > 0) v.put("startedAt", OtlpJson.instant(startedNanos).toString());
        if (lastEventNanos > 0) v.put("lastEventAt", OtlpJson.instant(lastEventNanos).toString());
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("tokens", tokens);
        totals.put("calls", calls);
        totals.put("cost", cost.toPlainString());
        v.put("totals", totals);
        v.put("runningAgents", open.values().stream().filter(o -> Semconv.KIND_AGENT.equals(o.kind())).count());
        v.put("incidents", incidents.openCount());
        v.put("security", openSecurityIncidents().size());
        contexts.entrySet().stream().map(e -> windowOf(e.getKey(), e.getValue()).fraction()).filter(java.util.Objects::nonNull).max(Double::compare).ifPresent(f -> v.put("contextFraction", f));
        if (incidents.worst() != null) v.put("worstIncident", incidents.worst());
        return v;
    }

    /** The instant of the last record applied, or null before any. */
    public synchronized Instant lastEventAt() {
        return lastEventNanos == 0 ? null : OtlpJson.instant(lastEventNanos);
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static BigDecimal decimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
