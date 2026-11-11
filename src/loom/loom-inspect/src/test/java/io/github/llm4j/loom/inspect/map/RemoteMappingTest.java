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

/** docs/specs/32-a2a-integration R37: a call to a remote agent is one span, across a suspension, and no text from outside is put in the record. */
class RemoteMappingTest {

    private static final Instant T0 = Instant.parse("2026-11-11T10:00:00Z");
    private static final String STEP = "Buy/s0";

    private static TraceEvent ev(double second, String type, String text, Object... kv) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) data.put((String) kv[i], kv[i + 1]);
        return new TraceEvent(type, null, STEP, text, data, T0.plusMillis((long) (second * 1000)));
    }

    private final List<LogRec> logs = new ArrayList<>();
    private final List<SpanRec> spans = new ArrayList<>();

    private void feed(TraceMapper m, TraceEvent... events) {
        for (TraceEvent e : events) {
            TraceMapper.Output o = m.map(e);
            logs.addAll(o.logs());
            spans.addAll(o.spans());
        }
    }

    @Test
    void aSendAndADoneMakeOneRemoteSpanThatNamesTheRemoteAndTheTask() {
        feed(new TraceMapper("r-1", Detail.FULL),
                ev(0, TraceEvent.REMOTE, "send Supplier", "kind", "send", "remote", "Supplier", "message_id", "m-1"),
                ev(1, TraceEvent.REMOTE, "state Supplier", "kind", "state", "remote", "Supplier", "task_id", "t-9", "task_state", "WORKING"),
                ev(5, TraceEvent.REMOTE, "done Supplier", "kind", "done", "remote", "Supplier"));
        assertThat(spans).hasSize(1);
        SpanRec span = spans.get(0);
        assertThat(span.attr(Semconv.LOOM_SPAN_KIND)).isEqualTo(Semconv.KIND_REMOTE);
        assertThat(span.attr(Semconv.LOOM_REMOTE)).isEqualTo("Supplier");
        assertThat(span.statusCode()).isEqualTo(SpanRec.STATUS_OK);
        assertThat(span.endNanos() - span.startNanos()).isEqualTo(5_000_000_000L);
    }

    @Test
    void theSpanSurvivesAResumeByAnotherProcessBecauseItsIdComesFromTheRunAndTheStep() {
        TraceMapper first = new TraceMapper("r-1", Detail.FULL);
        feed(first, ev(0, TraceEvent.REMOTE, "send Supplier", "kind", "send", "remote", "Supplier"));
        TraceMapper second = new TraceMapper("r-1", Detail.FULL);
        feed(second, ev(60, TraceEvent.REMOTE, "done Supplier", "kind", "done", "remote", "Supplier"));
        assertThat(spans).hasSize(1);
        assertThat(logs.stream().map(LogRec::spanId).filter(java.util.Objects::nonNull).findFirst()).contains(spans.get(0).spanId());
    }

    @Test
    void aFailureOfTheRemoteStepEndsTheSpanWithAnError() {
        TraceMapper m = new TraceMapper("r-1", Detail.FULL);
        feed(m, ev(0, TraceEvent.REMOTE, "send Supplier", "kind", "send", "remote", "Supplier"),
                ev(3, TraceEvent.FAILURE, "failed", "kind", "remote", "name", "Supplier", "step", STEP, "handled", false, "error", "the remote agent Supplier reported REJECTED"));
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).statusCode()).isEqualTo(SpanRec.STATUS_ERROR);
    }

    @Test
    void anExchangeIsALogWithADigestAndTheKeyAndNeverTheText() {
        feed(new TraceMapper("r-1", Detail.FULL),
                ev(0, TraceEvent.EXCHANGE, "question via Desk", "kind", "question", "channel", "Desk", "direction", "INBOUND_QUESTION", "digest", "ab12cd34ef56ab78", "key", "Buy/s0#r0/ex0"));
        assertThat(logs).isNotEmpty();
        String all = logs.toString();
        assertThat(all).contains("ab12cd34ef56ab78").contains("Desk");
    }
}
