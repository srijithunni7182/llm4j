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
    private String controlPending;
    private String controlBy;
    private boolean heldByOperator;
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
        detectors.rules(rules);
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
            case Semconv.EV_BUDGET -> detectors.onBudget(String.valueOf(r.text("loom.data.budget")), r.text("loom.data.kind"), r.body(), r.timeNanos());
            default -> { }
        }
        remember(r);
    }

    /** Applies a finished span. */
    public synchronized void apply(SpanRec s) {
        if (!finished.add(s.spanId())) return; // already counted
        touch(s.endNanos());
        Open was = open.remove(s.spanId());
        String kind = s.text(Semconv.LOOM_SPAN_KIND);
        if (kind == null) return;
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
            detectors.onRunEnd(r.timeNanos());
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
        Tool t = tools.computeIfAbsent(name, k -> new Tool());
        t.calls++;
        if (s.statusCode() == SpanRec.STATUS_ERROR) t.errors++;
        t.totalNanos += Math.max(0, s.endNanos() - s.startNanos());
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
            toolList.add(m);
        }
        v.put("tools", toolList);

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
