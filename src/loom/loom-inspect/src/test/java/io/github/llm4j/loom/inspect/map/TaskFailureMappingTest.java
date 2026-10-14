package io.github.llm4j.loom.inspect.map;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R23, R24, R27: a failed task or delegate, and the run it broke, in the records. */
class TaskFailureMappingTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");

    private static TraceEvent ev(double second, String type, String text, Object... kv) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) data.put((String) kv[i], kv[i + 1]);
        return new TraceEvent(type, null, "Main/s0", text, data, T0.plusMillis((long) (second * 1000)));
    }

    private final List<LogRec> logs = new ArrayList<>();
    private final List<SpanRec> spans = new ArrayList<>();

    private void feed(Detail detail, TraceEvent... events) {
        TraceMapper m = new TraceMapper("r-1", detail);
        for (TraceEvent e : events) {
            TraceMapper.Output o = m.map(e);
            logs.addAll(o.logs());
            spans.addAll(o.spans());
        }
    }

    private LogRec log(String name) {
        return logs.stream().filter(l -> name.equals(l.eventName())).findFirst().orElseThrow();
    }

    private TraceEvent[] failedTask(boolean handled) {
        return new TraceEvent[] {
                ev(0, TraceEvent.RUN_START, "", "workflow", "Main", "run", "r-1", "resumed", "false"),
                ev(0.1, TraceEvent.TASK_START, "run Boom", "task", "Boom", "step", "Main/s0"),
                ev(0.5, TraceEvent.TASK_END, "Boom failed: card 4242 declined", "task", "Boom", "step", "Main/s0", "outcome", "failed",
                        "error", "card 4242 declined for the customer in the long error text that is content", "attempts", 3, "category", "SERVER_ERROR",
                        "error_type", "IllegalStateException", "millis", 400L),
                ev(0.6, TraceEvent.FAILURE, "task Boom failed", "kind", "task", "name", "Boom", "step", "Main/s0", "category", "SERVER_ERROR",
                        "error_type", "IllegalStateException", "attempts", 3, "handled", handled, "error", "card 4242 declined for the customer in the long error text that is content")};
    }

    @Test
    void aFailedTaskSpanCarriesCategoryAttemptsAndType() {
        feed(Detail.FULL, failedTask(true));
        SpanRec s = spans.get(0);
        assertThat(s.statusCode()).isEqualTo(SpanRec.STATUS_ERROR);
        assertThat(s.text(Semconv.ERROR_TYPE)).isEqualTo("server_error");
        assertThat(s.attr(Semconv.LOOM_TASK_ATTEMPTS)).isEqualTo(3L);
    }

    @Test
    void aFailureEventBecomesAStructuredRecord() {
        feed(Detail.FULL, failedTask(true));
        LogRec f = log(Semconv.EV_FAILURE);
        assertThat(f.text(Semconv.LOOM_FAILURE_KIND)).isEqualTo("task");
        assertThat(f.text(Semconv.LOOM_FAILURE_NAME)).isEqualTo("Boom");
        assertThat(f.text(Semconv.LOOM_FAILURE_CATEGORY)).isEqualTo("server_error");
        assertThat(f.attr(Semconv.LOOM_FAILURE_HANDLED)).isEqualTo(true);
        assertThat(f.attr(Semconv.LOOM_FAILURE_ATTEMPTS)).isEqualTo(3L);
        assertThat(f.text(Semconv.LOOM_STEP)).isEqualTo("Main/s0");
        assertThat(f.severityNumber()).isEqualTo(LogRec.WARN);
    }

    @Test
    void anUnhandledFailureIsAnError() {
        feed(Detail.FULL, failedTask(false));
        assertThat(log(Semconv.EV_FAILURE).severityNumber()).isEqualTo(LogRec.ERROR);
    }

    @Test
    void theErrorTextFollowsTheDetailLevel() {
        feed(Detail.METADATA, failedTask(true));
        LogRec f = log(Semconv.EV_FAILURE);
        assertThat(f.attributes().toString()).doesNotContain("4242").doesNotContain("declined");
        assertThat(f.body()).doesNotContain("4242");
        assertThat(spans.get(0).statusMessage()).doesNotContain("4242");
        assertThat(f.text(Semconv.LOOM_FAILURE_NAME)).isEqualTo("Boom"); // structure stays
    }

    @Test
    void theRunRecordNamesWhatBrokeIt() {
        TraceEvent[] f = failedTask(false);
        List<TraceEvent> all = new ArrayList<>(List.of(f));
        all.add(ev(1, TraceEvent.RUN_END, "workflow Main failed", "workflow", "Main", "status", "failed", "error", "Task Boom failed: x",
                "failed_kind", "task", "failed_name", "Boom", "failed_step", "Main/s0", "failed_category", "SERVER_ERROR"));
        feed(Detail.FULL, all.toArray(new TraceEvent[0]));
        LogRec end = log(Semconv.EV_RUN_END);
        assertThat(end.text(Semconv.LOOM_RUN_FAILED_KIND)).isEqualTo("task");
        assertThat(end.text(Semconv.LOOM_RUN_FAILED_NAME)).isEqualTo("Boom");
        assertThat(end.text(Semconv.LOOM_RUN_FAILED_STEP)).isEqualTo("Main/s0");
        assertThat(end.text(Semconv.LOOM_RUN_FAILED_CATEGORY)).isEqualTo("server_error");
        SpanRec root = spans.stream().filter(s -> Semconv.KIND_WORKFLOW.equals(s.attr(Semconv.LOOM_SPAN_KIND))).findFirst().orElseThrow();
        assertThat(root.text(Semconv.LOOM_RUN_FAILED_NAME)).isEqualTo("Boom");
    }

    @Test
    void aRunEndWithoutFailureFieldsMapsAsBefore() {
        feed(Detail.FULL, ev(0, TraceEvent.RUN_START, "", "workflow", "Main", "run", "r-1", "resumed", "false"),
                ev(1, TraceEvent.RUN_END, "workflow Main failed", "workflow", "Main", "status", "failed", "error", "boom"));
        assertThat(log(Semconv.EV_RUN_END).attributes()).doesNotContainKey(Semconv.LOOM_RUN_FAILED_KIND);
    }

    @Test
    void anOldTaskEndWithoutTheNewFieldsStillMaps() {
        feed(Detail.FULL, ev(0.1, TraceEvent.TASK_START, "run Boom", "task", "Boom", "step", "Main/s0"),
                ev(0.5, TraceEvent.TASK_END, "Boom failed", "task", "Boom", "step", "Main/s0", "outcome", "failed", "error", "x"));
        assertThat(spans.get(0).statusCode()).isEqualTo(SpanRec.STATUS_ERROR);
        assertThat(spans.get(0).text(Semconv.ERROR_TYPE)).isEqualTo("task_failed");
    }
}
