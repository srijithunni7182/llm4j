package io.github.llm4j.loom.inspect.state;

import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records for the task-failure tests: a task span, a failure event and a run end, fed to a {@link
 * RunState}.
 */
final class TaskFailureFixtures {

    static final String RUN = "r-task";
    static final String TRACE = Ids.traceId(RUN);
    static final long T0 = OtlpJson.nanos(Instant.parse("2026-10-14T10:00:00Z"));

    final RunState state = new RunState("p", TRACE);
    private long seq;
    private int spanNo;

    static long at(double seconds) {
        return T0 + (long) (seconds * 1_000_000_000L);
    }

    void task(double endSec, String task, long ms, boolean ok, String category) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, Semconv.KIND_TASK);
        a.put(Semconv.LOOM_TASK, task);
        a.put(Semconv.LOOM_STEP, "Main/s0");
        a.put(Semconv.LOOM_TASK_OUTCOME, ok ? "ok" : "failed");
        if (!ok) a.put(Semconv.ERROR_TYPE, category);
        long end = at(endSec);
        state.apply(
                new SpanRec(
                        TRACE,
                        Ids.spanId(RUN, "k" + (++spanNo)),
                        null,
                        "loom.task " + task,
                        SpanRec.INTERNAL,
                        end - ms * 1_000_000L,
                        end,
                        a,
                        ok ? SpanRec.STATUS_OK : SpanRec.STATUS_ERROR,
                        null,
                        List.of()));
    }

    void failure(
            double sec, String kind, String name, String step, String category, boolean handled) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_FAILURE);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.LOOM_STEP, step);
        a.put(Semconv.LOOM_FAILURE_KIND, kind);
        a.put(Semconv.LOOM_FAILURE_NAME, name);
        a.put(Semconv.LOOM_FAILURE_CATEGORY, category);
        a.put(Semconv.LOOM_FAILURE_ATTEMPTS, 3L);
        a.put(Semconv.LOOM_FAILURE_HANDLED, handled);
        state.apply(
                "i_1",
                new LogRec(
                        at(sec), handled ? LogRec.WARN : LogRec.ERROR, "failed", a, TRACE, null));
    }

    void runEnd(double sec, String status, Object... failed) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.EVENT_NAME, Semconv.EV_RUN_END);
        a.put(Semconv.LOOM_SEQ, ++seq);
        a.put(Semconv.LOOM_RUN_STATUS, status);
        for (int i = 0; i < failed.length; i += 2) a.put((String) failed[i], failed[i + 1]);
        state.apply("i_1", new LogRec(at(sec), LogRec.ERROR, "end", a, TRACE, null));
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> incidents(String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<?>) state.view().get("incidents")) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (kind.equals(m.get("kind"))) out.add(m);
        }
        return out;
    }
}
