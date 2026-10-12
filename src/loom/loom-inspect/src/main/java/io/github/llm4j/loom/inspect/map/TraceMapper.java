package io.github.llm4j.loom.inspect.map;

import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns Loom's {@link TraceEvent}s into OpenTelemetry-shaped records: a log record when something starts or changes, and a span
 * when a step ends. One mapper per process instance and run; feed it every event in order. It is not thread-safe: the writer
 * thread of the emitter is the only caller.
 *
 * <p>Span ids are derived from the run id and the step id ({@link Ids}), so a process that wakes a sleeping run closes the span
 * the first process opened. Text leaves the mapper only as far as the {@link Detail} level allows.
 *
 * <p>What it cannot know yet: Loom's trace has no per-call event, so a step's tokens are the total the delegate reported
 * ({@code loom.usage.total_tokens}), not input and output separately, and a tool call is paired with its observation by order
 * within an agent. The error check on tool results is a heuristic (see {@link #looksLikeError}).
 */
public final class TraceMapper {

    /** What one event produced. */
    public record Output(List<LogRec> logs, List<SpanRec> spans) {
        static final Output NONE = new Output(List.of(), List.of());
    }

    private record Pending(String spanId, String name, String kind, String agent, String tool, String step, long startNanos, Map<String, Object> attrs) { }

    private static final Pattern ERROR_TEXT = Pattern.compile("^\\s*(error|failed|failure|exception|tool error)\\b|^\\s*(HTTP/\\d(\\.\\d)?\\s+)?[45]\\d\\d\\b", Pattern.CASE_INSENSITIVE);

    /** A data value this short is treated as structure (a name, a step id) and kept at every detail level. */
    private static final int SHORT = 80;

    private final Detail detail;
    private final String fallbackRunId;
    private String runId;
    private String traceId;
    private String workflow;
    private long runStartNanos;
    private long seq;
    private final Map<String, Pending> agents = new HashMap<>();
    private final Map<String, Deque<Pending>> tools = new HashMap<>();
    private final Map<String, Integer> toolCounts = new HashMap<>();
    private final Map<String, Pending> tasks = new HashMap<>();
    /** What each agent step was given and fetched, to check its answer's figures against. */
    private final Map<String, Signals.Corpus> corpora = new HashMap<>();
    private final Map<String, Pending> sleeps = new HashMap<>();

    /**
     * @param fallbackRunId the run id to use when the run reports none (an in-memory run); null to make one up
     */
    public TraceMapper(String fallbackRunId, Detail detail) {
        this.fallbackRunId = fallbackRunId;
        this.detail = detail == null ? Detail.TRUNCATED : detail;
    }

    /** The run id in use, once known. */
    public String runId() {
        return runId;
    }

    public String traceId() {
        return traceId;
    }

    public Output map(TraceEvent e) {
        ensureRun();
        List<LogRec> logs = new ArrayList<>();
        List<SpanRec> spans = new ArrayList<>();
        long now = OtlpJson.nanos(e.at());
        Map<String, Object> data = e.data() == null ? Map.of() : e.data();
        switch (e.type()) {
            case TraceEvent.RUN_START -> runStart(e, data, now, logs);
            case TraceEvent.RUN_END -> runEnd(e, data, now, logs, spans);
            case ProgressSampler.SAMPLE -> sample(data, now, logs);
            case TraceEvent.CONTROL -> control(e, data, now, logs);
            case TraceEvent.DELEGATE_START -> delegateStart(e, data, now, logs);
            case TraceEvent.DELEGATE_END -> delegateEnd(e, data, now, spans);
            case TraceEvent.DELEGATE_REPLAYED -> delegateReplayed(e, now, spans);
            case TraceEvent.ACTION -> toolStart(e, data, now, logs);
            case TraceEvent.OBSERVATION -> toolEnd(e, now, logs, spans);
            case TraceEvent.THOUGHT -> {
                closeUnanswered(e.agent(), now, spans);
                logs.add(log(now, LogRec.INFO, Semconv.EV_THOUGHT, detail.apply(e.text()), e, null));
            }
            case TraceEvent.BUDGET -> logs.add(log(now, LogRec.WARN, Semconv.EV_BUDGET, e.text(), e, data));
            case TraceEvent.TASK_START -> taskStart(e, data, now, logs);
            case TraceEvent.TASK_END -> taskEnd(e, data, now, spans);
            case TraceEvent.TASK_REPLAYED -> taskReplayed(e, data, now, spans);
            case TraceEvent.SLEEP -> sleep(e, data, now, logs, spans);
            case TraceEvent.NOTE -> logs.add(log(now, LogRec.INFO, Semconv.EV_NOTE, detail.apply(e.text()), e, null));
            default -> logs.add(log(now, LogRec.INFO, Semconv.EV_PREFIX + e.type(), detail.apply(e.text()), e, data));
        }
        return logs.isEmpty() && spans.isEmpty() ? Output.NONE : new Output(logs, spans);
    }

    /**
     * A workflow variable's hash at the boundary between two rounds of a loop, for the {@code no_progress} rule (read on the run's thread
     * by {@link ProgressSampler}). Only the hash is recorded, at every detail level: the value itself never leaves the process.
     */
    private void sample(Map<String, Object> data, long now, List<LogRec> logs) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_INSPECT_AGENT, text(data.get("agent"), ""));
        a.put(Semconv.LOOM_INSPECT_VAR, text(data.get("variable"), ""));
        a.put(Semconv.LOOM_INSPECT_LOOP, text(data.get("loop"), ""));
        a.put(Semconv.LOOM_INSPECT_HASH, text(data.get("hash"), ""));
        logs.add(record(now, LogRec.INFO, Semconv.EV_INSPECT_SAMPLE, text(data.get("variable"), "") + " sampled", a, null));
    }

    /** An observer's pause, resume, suspend or stop, and the step where it took effect. Who asked travels: it is for the audit trail. */
    private void control(TraceEvent e, Map<String, Object> data, long now, List<LogRec> logs) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_CONTROL_ACTION, text(data.get("action"), ""));
        a.put(Semconv.LOOM_CONTROL_STATE, text(data.get("state"), ""));
        if (data.get("by") != null) a.put(Semconv.LOOM_CONTROL_BY, String.valueOf(data.get("by")));
        if (data.get("step") != null) a.put(Semconv.LOOM_STEP, String.valueOf(data.get("step")));
        logs.add(record(now, LogRec.WARN, Semconv.EV_CONTROL, e.text(), a, null));
    }

    // ------------------------------------------------------------------------------ the run

    private void ensureRun() {
        if (runId != null) return;
        runId = fallbackRunId != null ? fallbackRunId : "run-" + UUID.randomUUID().toString().substring(0, 8);
        traceId = Ids.traceId(runId);
    }

    private void runStart(TraceEvent e, Map<String, Object> data, long now, List<LogRec> logs) {
        Object run = data.get("run");
        if (run != null && !String.valueOf(run).equals(runId)) {
            runId = String.valueOf(run);
            traceId = Ids.traceId(runId);
        }
        workflow = text(data.get("workflow"), "workflow");
        runStartNanos = now;
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_RUN_ID, runId);
        a.put(Semconv.LOOM_WORKFLOW, workflow);
        a.put(Semconv.LOOM_RESUMED, "true".equals(String.valueOf(data.get("resumed"))));
        logs.add(record(now, LogRec.INFO, Semconv.EV_RUN_START, "workflow " + workflow, a, null));
    }

    private void runEnd(TraceEvent e, Map<String, Object> data, long now, List<LogRec> logs, List<SpanRec> spans) {
        String status = text(data.get("status"), Semconv.STATUS_FAILED);
        boolean bad = Semconv.STATUS_FAILED.equals(status) || Semconv.STATUS_STOPPED.equals(status) || Semconv.STATUS_BUDGET.equals(status);
        if (Semconv.STATUS_SUSPENDED.equals(status)) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put(Semconv.LOOM_SUSPEND_REASON, text(data.get("reason"), "UNKNOWN"));
            if (data.get("resumeAt") != null) s.put(Semconv.LOOM_RESUME_AT, String.valueOf(data.get("resumeAt")));
            logs.add(record(now, LogRec.WARN, Semconv.EV_RUN_SUSPENDED, "run paused: " + s.get(Semconv.LOOM_SUSPEND_REASON), s, null));
        }
        // anything still open when a run fails or is stopped was cut short; when it is only suspended, whoever resumes it closes it
        if (bad) {
            String why = "run " + status;
            List<Pending> open = new ArrayList<>(agents.values());
            tools.values().forEach(open::addAll);
            open.addAll(tasks.values());
            for (Pending p : open) spans.add(span(p, now, SpanRec.STATUS_ERROR, why, Map.of()));
        }
        agents.clear();
        tools.clear();
        tasks.clear();
        sleeps.clear();

        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_RUN_STATUS, status);
        if (data.get("error") != null) a.put(Semconv.ERROR_TYPE, "run_failed");
        logs.add(record(now, bad ? LogRec.ERROR : LogRec.INFO, Semconv.EV_RUN_END, "workflow " + workflow + " " + status, a, null));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_WORKFLOW);
        root.put(Semconv.LOOM_WORKFLOW, workflow);
        root.put(Semconv.LOOM_RUN_ID, runId);
        root.put(Semconv.LOOM_RUN_STATUS, status);
        spans.add(new SpanRec(traceId, rootSpanId(), null, "loom.workflow " + workflow, SpanRec.INTERNAL, runStartNanos == 0 ? now : runStartNanos, now, root,
                bad ? SpanRec.STATUS_ERROR : SpanRec.STATUS_OK, bad ? text(data.get("error"), status) : null, List.of()));
    }

    private String rootSpanId() {
        return Ids.spanId(runId, "workflow");
    }

    // ------------------------------------------------------------------------------ agents

    private void delegateStart(TraceEvent e, Map<String, Object> data, long now, List<LogRec> logs) {
        String agent = e.agent();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.GEN_AI_OPERATION_NAME, Semconv.OP_INVOKE_AGENT);
        a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_AGENT);
        a.put(Semconv.LOOM_STEP, e.step());
        Pending p = new Pending(Ids.spanId(runId, e.step() + "#agent:" + agent), "invoke_agent " + agent, Semconv.KIND_AGENT, agent, null, e.step(), now, a);
        agents.put(agent + "@" + e.step(), p);
        corpora.computeIfAbsent(agent + "@" + e.step(), k -> new Signals.Corpus()).add(e.text());
        Map<String, Object> startAttrs = new LinkedHashMap<>(a);
        String t = detail.apply(e.text());
        if (t != null) startAttrs.put(Semconv.LOOM_TEXT, t);
        logs.add(record(now, LogRec.INFO, Semconv.EV_SPAN_START, p.name(), startAttrs, p.spanId()));
    }

    /**
     * A tool call that was started and never answered when its agent begins its next thought or finishes: the runtime blocks an exact
     * repeat of an earlier call and feeds the model an error, without reporting an observation. It is closed as having no result, so
     * the page does not show it in flight for the rest of the run.
     */
    private void closeUnanswered(String agent, long now, List<SpanRec> spans) {
        Deque<Pending> queue = agent == null ? null : tools.get(agent);
        if (queue == null) return;
        Pending p;
        while ((p = queue.pollFirst()) != null) {
            spans.add(span(p, now, SpanRec.STATUS_UNSET, null, Map.of(Semconv.LOOM_TOOL_NO_RESULT, true)));
        }
    }

    private void delegateEnd(TraceEvent e, Map<String, Object> data, long now, List<SpanRec> spans) {
        closeUnanswered(e.agent(), now, spans);
        Pending p = agents.remove(e.agent() + "@" + e.step());
        if (p == null) p = new Pending(Ids.spanId(runId, e.step() + "#agent:" + e.agent()), "invoke_agent " + e.agent(), Semconv.KIND_AGENT, e.agent(), null, e.step(), now, agentAttrs(e));
        Map<String, Object> extra = new LinkedHashMap<>();
        if (data.get("tokens") != null) extra.put(Semconv.LOOM_USAGE_TOTAL_TOKENS, number(data.get("tokens")));
        if (data.get("calls") != null) extra.put(Semconv.LOOM_USAGE_CALLS, number(data.get("calls")));
        if (data.get("promptTokens") != null) extra.put(Semconv.GEN_AI_USAGE_INPUT_TOKENS, number(data.get("promptTokens")));
        if (data.get("completionTokens") != null) extra.put(Semconv.GEN_AI_USAGE_OUTPUT_TOKENS, number(data.get("completionTokens")));
        if (data.get("cost") != null) extra.put(Semconv.LOOM_COST_USD, String.valueOf(data.get("cost")));
        String t = detail.apply(e.text());
        if (t != null) extra.put(Semconv.LOOM_TEXT, t);
        if (e.text() != null && !e.text().isBlank()) {
            extra.put(Semconv.LOOM_OUTPUT_HASH, Signals.outputHash(e.text()));
            Signals.Corpus corpus = corpora.remove(e.agent() + "@" + e.step());
            if (corpus != null) {
                Signals.Grounding g = Signals.ground(e.text(), corpus);
                extra.put(Semconv.LOOM_GROUNDING_FIGURES, (long) g.figures());
                extra.put(Semconv.LOOM_GROUNDING_UNGROUNDED, (long) g.ungrounded());
                if (!g.examples().isEmpty() && detail != Detail.METADATA) extra.put(Semconv.LOOM_GROUNDING_EXAMPLES, String.join(", ", g.examples()));
            }
        }
        spans.add(span(p, now, SpanRec.STATUS_OK, null, extra));
    }

    private void delegateReplayed(TraceEvent e, long now, List<SpanRec> spans) {
        Pending p = new Pending(Ids.spanId(runId, e.step() + "#agent:" + e.agent()), "invoke_agent " + e.agent(), Semconv.KIND_AGENT, e.agent(), null, e.step(), now, agentAttrs(e));
        spans.add(span(p, now, SpanRec.STATUS_OK, null, Map.of("loom.replayed", true)));
    }

    private static Map<String, Object> agentAttrs(TraceEvent e) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.GEN_AI_OPERATION_NAME, Semconv.OP_INVOKE_AGENT);
        a.put(Semconv.GEN_AI_AGENT_NAME, e.agent());
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_AGENT);
        a.put(Semconv.LOOM_STEP, e.step());
        return a;
    }

    // ------------------------------------------------------------------------------ tools

    private void toolStart(TraceEvent e, Map<String, Object> data, long now, List<LogRec> logs) {
        String tool = data.get("tool") != null ? String.valueOf(data.get("tool")) : firstWord(e.text());
        int n = toolCounts.merge(e.agent() + "@" + e.step(), 1, Integer::sum);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.GEN_AI_OPERATION_NAME, Semconv.OP_EXECUTE_TOOL);
        a.put(Semconv.GEN_AI_AGENT_NAME, e.agent());
        a.put(Semconv.GEN_AI_TOOL_NAME, tool);
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TOOL);
        a.put(Semconv.LOOM_STEP, e.step());
        a.put(Semconv.LOOM_ACTION_HASH, Signals.actionHash(tool, e.text()));
        corpora.computeIfAbsent(e.agent() + "@" + e.step(), k -> new Signals.Corpus()).add(e.text());
        Pending p = new Pending(Ids.spanId(runId, e.step() + "#tool:" + e.agent() + ":" + n), "execute_tool " + tool, Semconv.KIND_TOOL, e.agent(), tool, e.step(), now, a);
        tools.computeIfAbsent(e.agent(), k -> new ArrayDeque<>()).addLast(p);
        Map<String, Object> startAttrs = new LinkedHashMap<>(a);
        String input = detail.apply(e.text());
        if (input != null) startAttrs.put(Semconv.LOOM_TEXT, input);
        logs.add(record(now, LogRec.INFO, Semconv.EV_SPAN_START, p.name(), startAttrs, p.spanId()));
    }

    private void toolEnd(TraceEvent e, long now, List<LogRec> logs, List<SpanRec> spans) {
        Deque<Pending> queue = tools.get(e.agent());
        Pending p = queue == null ? null : queue.pollFirst();
        if (p == null) {
            logs.add(log(now, LogRec.INFO, Semconv.EV_OBSERVATION, detail.apply(e.text()), e, null));
            return;
        }
        boolean error = looksLikeError(e.text());
        Signals.Corpus seen = corpora.get(p.agent() + "@" + p.step());
        if (seen != null && !error) seen.add(e.text()); // what a failed call returned is not a source
        Map<String, Object> extra = new LinkedHashMap<>();
        String t = detail.apply(e.text());
        if (t != null) extra.put(Semconv.LOOM_TEXT, t);
        if (error) extra.put(Semconv.ERROR_TYPE, "tool_error");
        spans.add(span(p, now, error ? SpanRec.STATUS_ERROR : SpanRec.STATUS_OK, error ? firstLine(e.text()) : null, extra));
    }

    /**
     * A heuristic: Loom's trace gives a tool's result as text with no status, so a result that begins with "error", "failed" or
     * "exception", or with an HTTP 4xx or 5xx status, is treated as a failed call.
     */
    static boolean looksLikeError(String text) {
        return text != null && ERROR_TEXT.matcher(text).find();
    }

    // ------------------------------------------------------------------------------ tasks and sleeps

    private void taskStart(TraceEvent e, Map<String, Object> data, long now, List<LogRec> logs) {
        String task = text(data.get("task"), "task");
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TASK);
        a.put(Semconv.LOOM_TASK, task);
        a.put(Semconv.LOOM_STEP, e.step());
        Pending p = new Pending(Ids.spanId(runId, e.step() + "#task"), "loom.task " + task, Semconv.KIND_TASK, null, null, e.step(), now, a);
        tasks.put(e.step(), p);
        logs.add(record(now, LogRec.INFO, Semconv.EV_SPAN_START, p.name(), new LinkedHashMap<>(a), p.spanId()));
    }

    private void taskEnd(TraceEvent e, Map<String, Object> data, long now, List<SpanRec> spans) {
        String step = text(data.get("step"), e.step());
        Pending p = tasks.remove(step);
        String task = text(data.get("task"), "task");
        if (p == null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TASK);
            a.put(Semconv.LOOM_TASK, task);
            a.put(Semconv.LOOM_STEP, step);
            long millis = data.get("millis") == null ? 0 : number(data.get("millis"));
            p = new Pending(Ids.spanId(runId, step + "#task"), "loom.task " + task, Semconv.KIND_TASK, null, null, step, now - millis * 1_000_000L, a);
        }
        String outcome = text(data.get("outcome"), "ok");
        boolean failed = "failed".equals(outcome) || "unknown".equals(outcome);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put(Semconv.LOOM_TASK_OUTCOME, outcome);
        if (failed) extra.put(Semconv.ERROR_TYPE, "task_failed");
        spans.add(span(p, now, failed ? SpanRec.STATUS_ERROR : SpanRec.STATUS_OK, failed ? text(data.get("error"), outcome) : null, extra));
    }

    private void taskReplayed(TraceEvent e, Map<String, Object> data, long now, List<SpanRec> spans) {
        String task = text(data.get("task"), "task");
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TASK);
        a.put(Semconv.LOOM_TASK, task);
        a.put(Semconv.LOOM_STEP, e.step());
        a.put("loom.replayed", true);
        Pending p = new Pending(Ids.spanId(runId, e.step() + "#task"), "loom.task " + task, Semconv.KIND_TASK, null, null, e.step(), now, a);
        spans.add(span(p, now, SpanRec.STATUS_OK, null, Map.of()));
    }

    private void sleep(TraceEvent e, Map<String, Object> data, long now, List<LogRec> logs, List<SpanRec> spans) {
        String step = text(data.get("step"), e.step());
        String wakeAt = text(data.get("wakeAt"), null);
        long millis = data.get("millis") == null ? 0 : number(data.get("millis"));
        String spanId = Ids.spanId(runId, step + "#sleep");
        if ("start".equals(String.valueOf(data.get("state")))) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_SLEEP);
            a.put(Semconv.LOOM_STEP, step);
            if (wakeAt != null) a.put(Semconv.LOOM_SLEEP_WAKE_AT, wakeAt);
            Pending p = new Pending(spanId, "loom.sleep", Semconv.KIND_SLEEP, null, null, step, now, a);
            sleeps.put(step, p);
            logs.add(record(now, LogRec.INFO, Semconv.EV_SPAN_START, "loom.sleep", new LinkedHashMap<>(a), spanId));
            return;
        }
        Pending p = sleeps.remove(step);
        if (p == null) {
            // woken by a process that did not start the sleep: the start is the wake time less the duration, which is how long it was
            long wake = wakeAt == null ? now : OtlpJson.nanos(Instant.parse(wakeAt));
            Map<String, Object> a = new LinkedHashMap<>();
            a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_SLEEP);
            a.put(Semconv.LOOM_STEP, step);
            if (wakeAt != null) a.put(Semconv.LOOM_SLEEP_WAKE_AT, wakeAt);
            p = new Pending(spanId, "loom.sleep", Semconv.KIND_SLEEP, null, null, step, wake - millis * 1_000_000L, a);
        }
        spans.add(span(p, now, SpanRec.STATUS_OK, null, Map.of()));
    }

    // ------------------------------------------------------------------------------ building records

    private SpanRec span(Pending p, long end, int status, String message, Map<String, Object> extra) {
        Map<String, Object> a = new LinkedHashMap<>(p.attrs());
        a.putAll(extra);
        return new SpanRec(traceId, p.spanId(), rootSpanId(), p.name(), SpanRec.CLIENT, p.startNanos(), Math.max(end, p.startNanos()), a, status, message, List.of());
    }

    private LogRec log(long now, int severity, String event, String body, TraceEvent e, Map<String, Object> data) {
        Map<String, Object> a = new LinkedHashMap<>();
        if (e.agent() != null) a.put(Semconv.GEN_AI_AGENT_NAME, e.agent());
        if (e.step() != null && !e.step().isEmpty()) a.put(Semconv.LOOM_STEP, e.step());
        if (data != null) {
            for (Map.Entry<String, Object> d : data.entrySet()) {
                Object v = d.getValue();
                if (v instanceof Number || v instanceof Boolean) {
                    a.put("loom.data." + d.getKey(), v);
                } else if (v instanceof String str) {
                    String shown = str.length() <= SHORT ? str : detail.apply(str); // a short value is structure (a name, a step); a long one is content
                    if (shown != null) a.put("loom.data." + d.getKey(), shown);
                }
            }
        }
        return record(now, severity, event, body, a, null);
    }

    private LogRec record(long now, int severity, String event, String body, Map<String, Object> attrs, String spanId) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, event);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.putAll(attrs);
        return new LogRec(now, severity, body == null ? "" : body, a, traceId, spanId);
    }

    private static String text(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    /** A count as a long, whatever number type the runtime used (so the same value always travels as the same type). */
    private static long number(Object o) {
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String firstWord(String s) {
        if (s == null) return "tool";
        String t = s.trim();
        int i = t.indexOf(' ');
        return i < 0 ? t : t.substring(0, i);
    }

    private static String firstLine(String s) {
        if (s == null) return null;
        int i = s.indexOf('\n');
        String line = i < 0 ? s : s.substring(0, i);
        return line.length() <= 120 ? line : line.substring(0, 120) + "…";
    }
}
