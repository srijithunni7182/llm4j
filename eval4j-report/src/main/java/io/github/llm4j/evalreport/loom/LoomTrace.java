package io.github.llm4j.evalreport.loom;

import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.SpendReport;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.execution.TraceListener;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captures a Loom workflow run as a {@link WorkflowTrace} for eval4j's trajectory assertions and
 * the report's Trajectory view.
 *
 * <pre>{@code
 * LoomTrace trace = LoomTrace.attach(executor).workflow(def).expectPath("start", "n1", "n2", "end");
 * executor.initialize();           // add the listener before initialize()
 * executor.executeWorkflow(...);
 * WorkflowTrace wt = trace.finish(executor.spend());
 * WorkflowAssertions.assertThat(wt).followsExpectedPath().rewindsAtMost(20);
 * }</pre>
 *
 * <p>The listener is a bounded, thread-safe in-memory append and never throws (events arrive on
 * worker threads, possibly concurrently). Loom emits no event for an {@code alt} decision, so the
 * branch taken and the loop iterations are <em>inferred</em> from which delegations happened, and
 * each inferred decision is marked {@code inferred: true}.
 */
public final class LoomTrace {

    static final int MAX_EVENTS = 20_000;
    static final int MAX_TEXT = 2_000;
    private static final java.util.regex.Pattern CREDENTIAL_KEY =
            java.util.regex.Pattern.compile(
                    "(?i).*(token|secret|password|authorization|api[_-]?key).*");

    private final ConcurrentLinkedQueue<TraceEvent> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private String name = "workflow";
    private WorkflowGraph graph;
    private List<String> expected = List.of();
    private Double budgetUsd;
    private Integer rewindCap;

    private LoomTrace() {}

    /** A trace not yet attached to an executor; feed it through {@link #listener()}. */
    public static LoomTrace create() {
        return new LoomTrace();
    }

    /** Registers on the executor. Call before {@code executor.initialize()}. */
    public static LoomTrace attach(HarnessExecutor executor) {
        LoomTrace t = new LoomTrace();
        executor.addTraceListener(t.listener());
        return t;
    }

    public TraceListener listener() {
        return event -> {
            try {
                if (size.incrementAndGet() > MAX_EVENTS) {
                    dropped.incrementAndGet();
                    return;
                }
                buffer.add(event);
            } catch (RuntimeException ignored) {
                // a listener must never disturb the workflow
            }
        };
    }

    public LoomTrace named(String workflowName) {
        this.name = workflowName;
        return this;
    }

    /**
     * Supplies the workflow's structure (nodes and edges). Without it the trace has no graph
     * (LOOM-03).
     */
    public LoomTrace workflow(WorkflowDef def) {
        this.name = def.getName();
        this.graph = WorkflowGraph.of(def);
        return this;
    }

    /** Declares the node ids the run should visit, in order. */
    public LoomTrace expectPath(String... nodeIds) {
        this.expected = List.of(nodeIds);
        return this;
    }

    public LoomTrace budgetUsd(double usd) {
        this.budgetUsd = usd;
        return this;
    }

    public LoomTrace rewindCap(int cap) {
        this.rewindCap = cap;
        return this;
    }

    public WorkflowGraph graph() {
        return graph;
    }

    /** Builds the neutral trace. {@code spend} may be null. */
    public WorkflowTrace finish(SpendReport spend) {
        List<TraceEvent> raw = new ArrayList<>(buffer);
        raw.sort((a, b) -> a.at().compareTo(b.at()));
        Instant t0 = raw.isEmpty() ? Instant.now() : raw.get(0).at();
        List<WorkflowTrace.Event> events = new ArrayList<>();
        int rewinds = 0;
        for (TraceEvent e : raw) {
            if (TraceEvent.REWIND.equals(e.type())) {
                rewinds++;
            }
            events.add(
                    new WorkflowTrace.Event(
                            (e.at().toEpochMilli() - t0.toEpochMilli()) / 1000.0,
                            e.type(),
                            e.agent(),
                            e.step(),
                            null,
                            cut(e.text()),
                            clean(e.data())));
        }
        List<String> path = new ArrayList<>();
        if (graph != null) {
            events = infer(events, path);
        }
        if (dropped.get() > 0) {
            events.add(
                    new WorkflowTrace.Event(
                            events.isEmpty() ? 0 : events.get(events.size() - 1).t(),
                            "truncated",
                            null,
                            null,
                            null,
                            dropped.get() + " later events were not kept",
                            null));
        }
        List<WorkflowTrace.SpendLine> lines = new ArrayList<>();
        if (spend != null) {
            for (SpendReport.Line l : spend.lines()) {
                var c = l.charge();
                lines.add(
                        new WorkflowTrace.SpendLine(
                                l.step(),
                                l.agent(),
                                l.model(),
                                c.promptTokens(),
                                c.completionTokens(),
                                c.calls(),
                                c.cost() == null ? null : c.cost().doubleValue(),
                                c.estimated()));
            }
        }
        return new WorkflowTrace(
                name,
                graph == null ? List.of() : graph.nodes(),
                graph == null ? List.of() : graph.edges(),
                expected,
                path,
                events,
                lines,
                budgetUsd,
                rewinds,
                rewindCap);
    }

    /**
     * Maps delegations to graph nodes and builds the path taken. A delegation to agent X maps to
     * the next statement (in pre-order) that delegates to X, wrapping around for loops; enclosing
     * {@code alt} and {@code loop} nodes enter the path before it.
     */
    private List<WorkflowTrace.Event> infer(List<WorkflowTrace.Event> events, List<String> path) {
        List<WorkflowGraph.Slot> slots = graph.slots;
        List<WorkflowTrace.Event> out = new ArrayList<>();
        path.add(WorkflowGraph.START);
        // each agent keeps its own place in the script: agents inside a parallel round start in any
        // order
        Map<String, Integer> last = new java.util.HashMap<>();
        java.util.Set<Integer> entered = new java.util.HashSet<>();
        List<String> open = new ArrayList<>();
        for (WorkflowTrace.Event e : events) {
            String node = null;
            String actor = actorOf(e);
            if (actor != null) {
                int from = last.getOrDefault(actor, -1);
                int idx = -1;
                for (int i = from + 1; i < slots.size() && idx < 0; i++) {
                    if (belongs(slots.get(i), actor)) {
                        idx = i;
                    }
                }
                boolean wrapped = false;
                for (int i = 0; i <= from && idx < 0; i++) {
                    if (belongs(slots.get(i), actor)) {
                        idx = i;
                        wrapped = true;
                    }
                }
                if (idx >= 0) {
                    WorkflowGraph.Slot slot = slots.get(idx);
                    node = slot.node().id();
                    for (String anc : slot.ancestors()) {
                        boolean isLoop =
                                graph.nodes.stream()
                                        .anyMatch(
                                                n -> n.id().equals(anc) && "loop".equals(n.kind()));
                        if (!open.contains(anc) || (isLoop && wrapped)) {
                            path.add(anc);
                            if (!open.contains(anc)) {
                                open.add(anc);
                            }
                            if (!isLoop && slot.branches().get(anc) != null) {
                                out.add(
                                        new WorkflowTrace.Event(
                                                e.t(),
                                                "decision",
                                                null,
                                                e.step(),
                                                anc,
                                                slot.branches().get(anc),
                                                Map.of("inferred", true)));
                            }
                        }
                    }
                    if (slot.agents().isEmpty() || entered.add(idx)) {
                        path.add(node);
                    }
                    if (wrapped) {
                        entered.remove(idx);
                    }
                    last.put(actor, idx);
                }
            }
            out.add(
                    node == null
                            ? e
                            : new WorkflowTrace.Event(
                                    e.t(), e.type(), e.agent(), e.step(), node, e.text(),
                                    e.data()));
        }
        path.add(WorkflowGraph.END);
        return out;
    }

    /** Who a step belongs to: the agent of a delegation, or {@code task:Name} for a task; null for other events. */
    private static String actorOf(WorkflowTrace.Event e) {
        if (("delegate_start".equals(e.type()) || "delegate_replayed".equals(e.type())) && e.agent() != null) {
            return e.agent();
        }
        if (("task_start".equals(e.type()) || "task_replayed".equals(e.type()))
                && e.data() != null
                && e.data().get("task") != null) {
            return TASK_PREFIX + e.data().get("task");
        }
        return null;
    }

    private static final String TASK_PREFIX = "task:";

    private static boolean belongs(WorkflowGraph.Slot slot, String actor) {
        if (actor.startsWith(TASK_PREFIX)) {
            return actor.substring(TASK_PREFIX.length()).equals(slot.task());
        }
        return actor.equals(slot.node().agent()) || slot.agents().contains(actor);
    }

    private static String cut(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) + "…" : s;
    }

    /** Truncates text and drops values whose key looks like a credential (LOOM-04). */
    private static Map<String, Object> clean(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        data.forEach(
                (k, v) -> {
                    if (k == null || CREDENTIAL_KEY.matcher(k.toLowerCase(Locale.ROOT)).matches()) {
                        return;
                    }
                    out.put(k, v instanceof String s ? cut(s) : v);
                });
        return out.isEmpty() ? null : out;
    }
}
