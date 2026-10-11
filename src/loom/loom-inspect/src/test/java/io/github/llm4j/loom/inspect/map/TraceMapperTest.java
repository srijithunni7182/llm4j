package io.github.llm4j.loom.inspect.map;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Loom trace events in, OpenTelemetry-shaped records out, and the console's view of them. Hand-built events, no model, no clock. */
class TraceMapperTest {

    private static final Instant T0 = Instant.parse("2026-10-11T14:02:00Z");
    private static final String RUN = "r-7f3a";

    private static TraceEvent ev(int second, String type, String agent, String step, String text, Object... kv) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) data.put((String) kv[i], kv[i + 1]);
        return new TraceEvent(type, agent, step, text, data, T0.plusSeconds(second));
    }

    private static TraceMapper.Output feed(TraceMapper m, List<LogRec> logs, List<SpanRec> spans, TraceEvent... events) {
        TraceMapper.Output last = null;
        for (TraceEvent e : events) {
            last = m.map(e);
            logs.addAll(last.logs());
            spans.addAll(last.spans());
        }
        return last;
    }

    private static List<TraceEvent> firstProcess() {
        return List.of(
                ev(0, TraceEvent.RUN_START, null, "", "workflow ContentStudio", "workflow", "ContentStudio", "run", RUN, "resumed", "false"),
                ev(1, TraceEvent.DELEGATE_START, "Editor", "ContentStudio/s0", "Edit this script", "variable", "edit"),
                ev(2, TraceEvent.THOUGHT, "Editor", "ContentStudio/s0", "I should check a hook pattern"),
                ev(3, TraceEvent.ACTION, "Editor", "ContentStudio/s0", "Wiki {\"path\":\"/w/api.php\"}", "tool", "Wiki"),
                ev(5, TraceEvent.OBSERVATION, "Editor", "ContentStudio/s0", "200 OK {\"query\":{}}"),
                ev(6, TraceEvent.ACTION, "Editor", "ContentStudio/s0", "Example {\"path\":\"/pricing\"}", "tool", "Example"),
                ev(7, TraceEvent.OBSERVATION, "Editor", "ContentStudio/s0", "404 Not Found"),
                ev(20, TraceEvent.DELEGATE_END, "Editor", "ContentStudio/s0", "score 5", "calls", 3, "tokens", 5000, "cost", "0.27"),
                ev(21, TraceEvent.TASK_START, null, "ContentStudio/s1", "run Check", "task", "Check", "step", "ContentStudio/s1"),
                ev(22, TraceEvent.TASK_END, null, "ContentStudio/s1", "Check → ok", "task", "Check", "step", "ContentStudio/s1", "outcome", "ok", "millis", 900),
                ev(23, TraceEvent.SLEEP, null, "ContentStudio/s2", "sleeping 90s", "state", "start", "wakeAt", "2026-10-11T14:03:53Z", "millis", "90000", "step", "ContentStudio/s2"),
                ev(23, TraceEvent.SUSPENDED, null, "", "paused at ContentStudio/s2 (sleeping 90s); resumes at 2026-10-11T14:03:53Z"),
                ev(23, TraceEvent.RUN_END, null, "", "workflow ContentStudio suspended", "workflow", "ContentStudio", "run", RUN, "status", "suspended", "reason", "SLEEP", "resumeAt", "2026-10-11T14:03:53Z"));
    }

    private static List<TraceEvent> secondProcess() {
        return List.of(
                ev(100, TraceEvent.RUN_START, null, "", "workflow ContentStudio", "workflow", "ContentStudio", "run", RUN, "resumed", "true"),
                ev(100, TraceEvent.SLEEP, null, "ContentStudio/s2", "woke after 90s", "state", "end", "wakeAt", "2026-10-11T14:03:53Z", "millis", "90000", "step", "ContentStudio/s2"),
                ev(101, TraceEvent.DELEGATE_START, "Scriptwriter", "ContentStudio/s3", "Write the script"),
                ev(130, TraceEvent.DELEGATE_END, "Scriptwriter", "ContentStudio/s3", "done", "calls", 2, "tokens", 1200, "cost", "0.10"),
                ev(131, TraceEvent.RUN_END, null, "", "workflow ContentStudio completed", "workflow", "ContentStudio", "run", RUN, "status", "completed"));
    }

    private static SpanRec spanNamed(List<SpanRec> spans, String name) {
        return spans.stream().filter(s -> name.equals(s.name())).findFirst().orElseThrow();
    }

    @Test
    void anAgentStepBecomesAStartRecordAndASpanWithItsUsage() {
        TraceMapper m = new TraceMapper(null, Detail.FULL);
        List<LogRec> logs = new ArrayList<>();
        List<SpanRec> spans = new ArrayList<>();
        feed(m, logs, spans, firstProcess().subList(0, 8).toArray(new TraceEvent[0]));
        LogRec start = logs.stream().filter(l -> l.eventName().equals(Semconv.EV_SPAN_START) && "invoke_agent Editor".equals(l.body())).findFirst().orElseThrow();
        assertThat(start.text(Semconv.LOOM_SPAN_KIND)).isEqualTo("agent");
        assertThat(start.spanId()).isEqualTo(Ids.spanId(RUN, "ContentStudio/s0#agent:Editor"));
        assertThat(start.traceId()).isEqualTo(Ids.traceId(RUN));

        SpanRec editor = spanNamed(spans, "invoke_agent Editor");
        assertThat(editor.spanId()).isEqualTo(start.spanId());
        assertThat(editor.text(Semconv.GEN_AI_OPERATION_NAME)).isEqualTo("invoke_agent");
        assertThat(editor.attr(Semconv.LOOM_USAGE_TOTAL_TOKENS)).isEqualTo(5000L);
        assertThat(editor.attr(Semconv.LOOM_USAGE_CALLS)).isEqualTo(3L);
        assertThat(editor.text(Semconv.LOOM_COST_USD)).isEqualTo("0.27");
        assertThat(editor.endNanos() - editor.startNanos()).isEqualTo(19_000_000_000L);
        assertThat(editor.parentSpanId()).isEqualTo(Ids.spanId(RUN, "workflow"));
    }

    @Test
    void toolCallsArePairedWithTheirObservationsAndAFailedOneIsMarkedAnError() {
        TraceMapper m = new TraceMapper(null, Detail.FULL);
        List<LogRec> logs = new ArrayList<>();
        List<SpanRec> spans = new ArrayList<>();
        feed(m, logs, spans, firstProcess().subList(0, 8).toArray(new TraceEvent[0]));
        SpanRec wiki = spanNamed(spans, "execute_tool Wiki");
        SpanRec example = spanNamed(spans, "execute_tool Example");
        assertThat(wiki.statusCode()).isEqualTo(SpanRec.STATUS_OK);
        assertThat(wiki.endNanos() - wiki.startNanos()).isEqualTo(2_000_000_000L);
        assertThat(wiki.text(Semconv.GEN_AI_TOOL_NAME)).isEqualTo("Wiki");
        assertThat(example.statusCode()).isEqualTo(SpanRec.STATUS_ERROR);
        assertThat(example.text(Semconv.ERROR_TYPE)).isEqualTo("tool_error");
        assertThat(wiki.spanId()).isNotEqualTo(example.spanId());
    }

    @Test
    void theErrorHeuristicRecognisesFailuresAndNotOrdinaryText() {
        assertThat(TraceMapper.looksLikeError("Error: connection refused")).isTrue();
        assertThat(TraceMapper.looksLikeError("failed to fetch")).isTrue();
        assertThat(TraceMapper.looksLikeError("404 Not Found")).isTrue();
        assertThat(TraceMapper.looksLikeError("HTTP/1.1 503 Service Unavailable")).isTrue();
        assertThat(TraceMapper.looksLikeError("200 OK {\"ok\":true}")).isFalse();
        assertThat(TraceMapper.looksLikeError("No errors were found in 404 pages")).isFalse();
        assertThat(TraceMapper.looksLikeError(null)).isFalse();
    }

    @Test
    void everyLogRecordGetsTheNextSequenceNumber() {
        TraceMapper m = new TraceMapper(null, Detail.FULL);
        List<LogRec> logs = new ArrayList<>();
        feed(m, logs, new ArrayList<>(), firstProcess().toArray(new TraceEvent[0]));
        for (int i = 0; i < logs.size(); i++) assertThat(logs.get(i).seq()).isEqualTo(i + 1L);
    }

    @Test
    void aSuspendedRunSaysWhyAndTheSleepSpanIsLeftOpenForWhoeverWakesIt() {
        TraceMapper m = new TraceMapper(null, Detail.FULL);
        List<LogRec> logs = new ArrayList<>();
        List<SpanRec> spans = new ArrayList<>();
        feed(m, logs, spans, firstProcess().toArray(new TraceEvent[0]));
        LogRec suspended = logs.stream().filter(l -> l.eventName().equals(Semconv.EV_RUN_SUSPENDED)).findFirst().orElseThrow();
        assertThat(suspended.text(Semconv.LOOM_SUSPEND_REASON)).isEqualTo("SLEEP");
        assertThat(suspended.text(Semconv.LOOM_RESUME_AT)).isEqualTo("2026-10-11T14:03:53Z");
        assertThat(logs.get(logs.size() - 1).eventName()).isEqualTo(Semconv.EV_RUN_END);
        assertThat(spans).noneMatch(s -> "loom.sleep".equals(s.name()));
    }

    @Test
    void theProcessThatWakesASleepClosesTheSpanTheFirstOneOpened() {
        TraceMapper first = new TraceMapper(null, Detail.FULL);
        List<LogRec> firstLogs = new ArrayList<>();
        feed(first, firstLogs, new ArrayList<>(), firstProcess().toArray(new TraceEvent[0]));
        String openedAs = firstLogs.stream().filter(l -> "loom.sleep".equals(l.body()) && l.eventName().equals(Semconv.EV_SPAN_START)).findFirst().orElseThrow().spanId();

        TraceMapper second = new TraceMapper(null, Detail.FULL);
        List<SpanRec> spans = new ArrayList<>();
        feed(second, new ArrayList<>(), spans, secondProcess().toArray(new TraceEvent[0]));
        SpanRec sleep = spanNamed(spans, "loom.sleep");
        assertThat(sleep.spanId()).isEqualTo(openedAs);
        long wake = OtlpJson.nanos(Instant.parse("2026-10-11T14:03:53Z"));
        assertThat(sleep.startNanos()).isEqualTo(wake - 90_000_000_000L);
        assertThat(sleep.traceId()).isEqualTo(Ids.traceId(RUN));
    }

    @Test
    void aWholeSleptRunReadsCorrectlyInTheConsoleAcrossTwoProcesses() {
        RunRegistry registry = new RunRegistry();
        Resource one = Resource.of("getviral-prod", "i_1");
        Resource two = Resource.of("getviral-prod", "i_2");
        for (int pass = 0; pass < 2; pass++) {
            TraceMapper m = new TraceMapper(null, Detail.FULL);
            List<LogRec> logs = new ArrayList<>();
            List<SpanRec> spans = new ArrayList<>();
            feed(m, logs, spans, (pass == 0 ? firstProcess() : secondProcess()).toArray(new TraceEvent[0]));
            Resource r = pass == 0 ? one : two;
            // a round trip through OTLP/JSON, as it would travel
            for (OtlpJson.Logs l : OtlpJson.decodeLogs(OtlpJson.encodeLogs(r, "0.1", logs))) registry.applyLogs(l.resource(), l.records());
            for (OtlpJson.Traces t : OtlpJson.decodeTraces(OtlpJson.encodeTraces(r, "0.1", spans))) registry.applySpans(t.resource(), t.spans());
            if (pass == 0) assertThat(registry.find("getviral-prod", Ids.traceId(RUN)).orElseThrow().view().get("status")).isEqualTo("sleeping");
        }
        Map<String, Object> v = registry.find("getviral-prod", Ids.traceId(RUN)).orElseThrow().view();
        assertThat(v.get("status")).isEqualTo("finished");
        assertThat(v.get("workflow")).isEqualTo("ContentStudio");
        assertThat(v.get("startedAt")).isEqualTo("2026-10-11T14:02:00Z");
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) v.get("totals");
        assertThat(totals).containsEntry("tokens", 6200L).containsEntry("calls", 5L).containsEntry("cost", "0.37");
        assertThat(v.get("gaps")).isEqualTo(0L);
        assertThat(v.get("duplicates")).isEqualTo(0L);
    }

    @Test
    void whenARunFailsWhatWasStillOpenIsClosedAsAnError() {
        TraceMapper m = new TraceMapper(null, Detail.FULL);
        List<SpanRec> spans = new ArrayList<>();
        feed(m, new ArrayList<>(), spans,
                ev(0, TraceEvent.RUN_START, null, "", "w", "workflow", "W", "run", RUN),
                ev(1, TraceEvent.DELEGATE_START, "Editor", "W/s0", "x"),
                ev(2, TraceEvent.ACTION, "Editor", "W/s0", "Wiki {}", "tool", "Wiki"),
                ev(9, TraceEvent.RUN_END, null, "", "failed", "workflow", "W", "run", RUN, "status", "failed", "error", "boom"));
        assertThat(spanNamed(spans, "invoke_agent Editor").statusCode()).isEqualTo(SpanRec.STATUS_ERROR);
        assertThat(spanNamed(spans, "execute_tool Wiki").statusCode()).isEqualTo(SpanRec.STATUS_ERROR);
        SpanRec root = spanNamed(spans, "loom.workflow W");
        assertThat(root.statusCode()).isEqualTo(SpanRec.STATUS_ERROR);
        assertThat(root.statusMessage()).isEqualTo("boom");
        assertThat(root.parentSpanId()).isNull();
    }

    @Test
    void metadataLevelSendsNoTextAndTruncatedCutsLongText() {
        String longText = "y".repeat(900);
        for (Detail level : Detail.values()) {
            TraceMapper m = new TraceMapper(RUN, level);
            List<LogRec> logs = new ArrayList<>();
            List<SpanRec> spans = new ArrayList<>();
            feed(m, logs, spans,
                    ev(0, TraceEvent.DELEGATE_START, "A", "W/s0", longText),
                    ev(1, TraceEvent.THOUGHT, "A", "W/s0", longText),
                    ev(2, TraceEvent.DELEGATE_END, "A", "W/s0", longText, "tokens", 10, "calls", 1));
            LogRec thought = logs.stream().filter(l -> l.eventName().equals(Semconv.EV_THOUGHT)).findFirst().orElseThrow();
            Object shown = spans.get(0).attr(Semconv.LOOM_TEXT);
            switch (level) {
                case FULL -> {
                    assertThat(thought.body()).isEqualTo(longText);
                    assertThat(shown).isEqualTo(longText);
                }
                case TRUNCATED -> {
                    assertThat(thought.body()).hasSize(Detail.LIMIT + 1);
                    assertThat(String.valueOf(shown)).hasSize(Detail.LIMIT + 1);
                }
                case METADATA -> {
                    assertThat(thought.body()).isEmpty();
                    assertThat(shown).isNull();
                    assertThat(logs.get(0).attr(Semconv.LOOM_TEXT)).isNull();
                    assertThat(spans.get(0).attr(Semconv.LOOM_USAGE_TOTAL_TOKENS)).isEqualTo(10L); // structure and numbers still flow
                }
            }
        }
    }

    @Test
    void aRunWithNoRunIdUsesTheFallbackOrMakesOneUp() {
        TraceMapper withFallback = new TraceMapper("journal-dir-1", Detail.FULL);
        withFallback.map(ev(0, TraceEvent.NOTE, null, "", "hello"));
        assertThat(withFallback.runId()).isEqualTo("journal-dir-1");
        TraceMapper made = new TraceMapper(null, Detail.FULL);
        made.map(ev(0, TraceEvent.NOTE, null, "", "hello"));
        assertThat(made.runId()).startsWith("run-");
        assertThat(made.traceId()).isEqualTo(Ids.traceId(made.runId()));
    }

    @Test
    void otherEventTypesBecomeLogRecordsNamedAfterThem() {
        TraceMapper m = new TraceMapper(RUN, Detail.FULL);
        LogRec rewind = m.map(ev(0, TraceEvent.REWIND, null, "W/s4", "rewind to polish", "generation", 2)).logs().get(0);
        assertThat(rewind.eventName()).isEqualTo("loom.rewind");
        assertThat(rewind.attr("loom.data.generation")).isEqualTo(2);
        assertThat(rewind.text(Semconv.LOOM_STEP)).isEqualTo("W/s4");
    }
}
