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

/** R1 to R7: the events Phase 3 added to the Loom trace become spans and logs, with the real outcome and no text. */
class NewEventsMappingTest {

    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private static final String STEP = "Main/s0";

    private static TraceEvent ev(double second, String type, String agent, String text, Object... kv) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) data.put((String) kv[i], kv[i + 1]);
        return new TraceEvent(type, agent, STEP, text, data, T0.plusMillis((long) (second * 1000)));
    }

    private final List<LogRec> logs = new ArrayList<>();
    private final List<SpanRec> spans = new ArrayList<>();

    private TraceMapper feed(TraceEvent... events) {
        TraceMapper m = new TraceMapper("r-1", Detail.FULL);
        for (TraceEvent e : events) {
            TraceMapper.Output o = m.map(e);
            logs.addAll(o.logs());
            spans.addAll(o.spans());
        }
        return m;
    }

    private SpanRec span(String kind) {
        return spans.stream().filter(s -> kind.equals(s.attr(Semconv.LOOM_SPAN_KIND))).findFirst().orElseThrow();
    }

    private static TraceEvent modelCall(double at, int iteration, Object... kv) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("model", "m1");
        d.put("requested_model", "m1");
        d.put("iteration", iteration);
        d.put("duration_ms", 400L);
        d.put("waited_ms", 0L);
        d.put("attempts", 1);
        d.put("prompt_tokens", 100L);
        d.put("completion_tokens", 20L);
        d.put("finish_reason", "tool_calls");
        d.put("truncated", false);
        d.put("outcome", "OK");
        for (int i = 0; i < kv.length; i += 2) d.put((String) kv[i], kv[i + 1]);
        return new TraceEvent(TraceEvent.MODEL_CALL, "Editor", STEP, "m1 400 ms", d, T0.plusMillis((long) (at * 1000)));
    }

    private static TraceEvent toolCall(double at, String tool, Object... kv) {
        Map<String, Object> d = new LinkedHashMap<>(Map.of("tool", tool, "outcome", "EXECUTED", "duration_ms", 120L, "approval_ms", 0L, "result_chars", 40L, "soft_failure", false));
        for (int i = 0; i < kv.length; i += 2) d.put((String) kv[i], kv[i + 1]);
        return new TraceEvent(TraceEvent.TOOL_CALL, "Editor", STEP, tool + " ok", d, T0.plusMillis((long) (at * 1000)));
    }

    private TraceEvent[] start() {
        return new TraceEvent[] {ev(0, TraceEvent.RUN_START, null, "", "workflow", "Main", "run", "r-1", "resumed", "false"),
                ev(0.1, TraceEvent.DELEGATE_START, "Editor", "edit")};
    }

    private void feedAll(TraceEvent[] first, TraceEvent... rest) {
        List<TraceEvent> all = new ArrayList<>(List.of(first));
        all.addAll(List.of(rest));
        feed(all.toArray(new TraceEvent[0]));
    }

    // ── tool calls (R1, R2, R3) ─────────────────────────────────────────────────

    @Test
    void theRealOutcomeDecidesTheStatusNotTheText() {
        feedAll(start(), modelCall(0.5, 0),
                ev(0.6, TraceEvent.ACTION, "Editor", "Wiki {}", "tool", "Wiki"),
                ev(0.8, TraceEvent.OBSERVATION, "Editor", "Error: this text looks like a failure but the tool succeeded"),
                toolCall(0.81, "Wiki"));
        SpanRec tool = span(Semconv.KIND_TOOL);
        assertThat(tool.statusCode()).isEqualTo(SpanRec.STATUS_OK);
        assertThat(tool.attr(Semconv.LOOM_TOOL_OUTCOME)).isEqualTo("EXECUTED");
        assertThat(tool.attr(Semconv.LOOM_TOOL_SOFT_FAILURE)).isEqualTo(false);
        assertThat(tool.attr(Semconv.ERROR_TYPE)).isNull();
    }

    @Test
    void aSoftFailureIsAnErrorWithItsCategory() {
        feedAll(start(), modelCall(0.5, 0),
                ev(0.6, TraceEvent.ACTION, "Editor", "Wiki {}", "tool", "Wiki"),
                ev(0.8, TraceEvent.OBSERVATION, "Editor", "no page found"),
                toolCall(0.81, "Wiki", "soft_failure", true, "category", "NOT_FOUND"));
        SpanRec tool = span(Semconv.KIND_TOOL);
        assertThat(tool.statusCode()).isEqualTo(SpanRec.STATUS_ERROR);
        assertThat(tool.attr(Semconv.ERROR_TYPE)).isEqualTo("not_found");
        assertThat(tool.attr(Semconv.LOOM_TOOL_SOFT_FAILURE)).isEqualTo(true);
    }

    @Test
    void aCallThatWasNeverRunHasNoResultAndNoErrorStatus() {
        feedAll(start(), modelCall(0.5, 0),
                ev(0.6, TraceEvent.ACTION, "Editor", "Wiki {}", "tool", "Wiki"),
                toolCall(0.7, "Wiki", "outcome", "DUPLICATE_BLOCKED", "category", "REPEAT_BLOCKED"));
        SpanRec tool = span(Semconv.KIND_TOOL);
        assertThat(tool.statusCode()).isEqualTo(SpanRec.STATUS_UNSET);
        assertThat(tool.attr(Semconv.LOOM_TOOL_NO_RESULT)).isEqualTo(true);
        assertThat(tool.attr(Semconv.LOOM_TOOL_OUTCOME)).isEqualTo("DUPLICATE_BLOCKED");
    }

    @Test
    void anOldTraceWithoutTheNewEventsMapsAsBefore() {
        feedAll(start(),
                ev(0.6, TraceEvent.ACTION, "Editor", "Wiki {}", "tool", "Wiki"),
                ev(0.8, TraceEvent.OBSERVATION, "Editor", "404 Not Found"));
        assertThat(spans).as("emitted at the observation, as before").hasSize(1);
        assertThat(spans.get(0).statusCode()).isEqualTo(SpanRec.STATUS_ERROR);
        assertThat(spans.get(0).attr(Semconv.ERROR_TYPE)).isEqualTo("tool_error");
        assertThat(spans.get(0).attr(Semconv.LOOM_TOOL_OUTCOME)).isNull();
    }

    @Test
    void theSpanIsHeldUntilItsToolCallArrives() {
        TraceMapper m = feed(start());
        m.map(modelCall(0.5, 0));
        m.map(ev(0.6, TraceEvent.ACTION, "Editor", "Wiki {}", "tool", "Wiki"));
        assertThat(m.map(ev(0.8, TraceEvent.OBSERVATION, "Editor", "ok")).spans()).isEmpty();
        assertThat(m.map(toolCall(0.81, "Wiki")).spans()).hasSize(1);
    }

    @Test
    void aHeldSpanIsFlushedByTheAgentsNextEvent() {
        TraceMapper m = feed(start());
        m.map(modelCall(0.5, 0));
        m.map(ev(0.6, TraceEvent.ACTION, "Editor", "Wiki {}", "tool", "Wiki"));
        m.map(ev(0.8, TraceEvent.OBSERVATION, "Editor", "ok"));
        TraceMapper.Output o = m.map(ev(1.0, TraceEvent.ACTION, "Editor", "Wiki {\"x\":1}", "tool", "Wiki"));
        assertThat(o.spans()).hasSize(1);
        assertThat(o.spans().get(0).attr(Semconv.LOOM_TOOL_OUTCOME)).as("no tool_call came: the text heuristic").isNull();
    }

    @Test
    void aHeldSpanIsFlushedByTheStepEndAndTheRunEnd() {
        TraceMapper a = feed(start());
        a.map(modelCall(0.5, 0));
        a.map(ev(0.6, TraceEvent.ACTION, "Editor", "Wiki {}", "tool", "Wiki"));
        a.map(ev(0.8, TraceEvent.OBSERVATION, "Editor", "ok"));
        assertThat(a.map(ev(1.0, TraceEvent.DELEGATE_END, "Editor", "done", "tokens", 10)).spans()).hasSize(2);

        TraceMapper b = new TraceMapper("r-1", Detail.FULL);
        for (TraceEvent e : start()) b.map(e);
        b.map(modelCall(0.5, 0));
        b.map(ev(0.6, TraceEvent.ACTION, "Editor", "Wiki {}", "tool", "Wiki"));
        b.map(ev(0.8, TraceEvent.OBSERVATION, "Editor", "ok"));
        assertThat(b.map(ev(1.0, TraceEvent.RUN_END, null, "", "workflow", "Main", "run", "r-1", "status", "suspended", "reason", "SLEEP")).spans())
                .as("the held tool span and the root").hasSize(2);
    }

    @Test
    void noSpanIsLostOrEmittedTwiceInAnyInterleaving() {
        TraceEvent[] seq = {
                ev(0.6, TraceEvent.ACTION, "Editor", "Wiki {\"a\":1}", "tool", "Wiki"), ev(0.7, TraceEvent.OBSERVATION, "Editor", "one"), toolCall(0.71, "Wiki"),
                ev(0.8, TraceEvent.ACTION, "Editor", "Wiki {\"a\":2}", "tool", "Wiki"), ev(0.9, TraceEvent.OBSERVATION, "Editor", "two"),
                ev(1.0, TraceEvent.ACTION, "Editor", "Wiki {\"a\":3}", "tool", "Wiki"), ev(1.1, TraceEvent.OBSERVATION, "Editor", "three"), toolCall(1.11, "Wiki")};
        feedAll(start(), modelCall(0.5, 0));
        TraceMapper m = new TraceMapper("r-2", Detail.FULL);
        List<SpanRec> out = new ArrayList<>();
        for (TraceEvent e : start()) out.addAll(m.map(e).spans());
        out.addAll(m.map(modelCall(0.5, 0)).spans());
        for (TraceEvent e : seq) out.addAll(m.map(e).spans());
        out.addAll(m.map(ev(2, TraceEvent.DELEGATE_END, "Editor", "done")).spans());
        List<SpanRec> tools = out.stream().filter(s -> Semconv.KIND_TOOL.equals(s.attr(Semconv.LOOM_SPAN_KIND))).toList();
        assertThat(tools).hasSize(3);
        assertThat(tools.stream().map(SpanRec::spanId).distinct()).hasSize(3);
    }

    @Test
    void twoAgentsHoldTheirOwnSpans() {
        TraceMapper m = feed(start());
        m.map(ev(0.2, TraceEvent.DELEGATE_START, "Writer", "write"));
        m.map(modelCall(0.5, 0));
        m.map(ev(0.6, TraceEvent.ACTION, "Editor", "Wiki {}", "tool", "Wiki"));
        m.map(ev(0.61, TraceEvent.ACTION, "Writer", "Wiki {}", "tool", "Wiki"));
        m.map(ev(0.8, TraceEvent.OBSERVATION, "Editor", "ok"));
        m.map(ev(0.82, TraceEvent.OBSERVATION, "Writer", "no page"));
        List<SpanRec> out = new ArrayList<>(m.map(new TraceEvent(TraceEvent.TOOL_CALL, "Writer", STEP, "x", Map.of("tool", "Wiki", "outcome", "EXECUTED", "soft_failure", true, "category", "NOT_FOUND",
                "duration_ms", 5L, "approval_ms", 0L, "result_chars", 3L), T0.plusMillis(830))).spans());
        out.addAll(m.map(toolCall(0.84, "Wiki")).spans());
        assertThat(out).hasSize(2);
        assertThat(out.get(0).attr(Semconv.GEN_AI_AGENT_NAME)).isEqualTo("Writer");
        assertThat(out.get(0).statusCode()).isEqualTo(SpanRec.STATUS_ERROR);
        assertThat(out.get(1).attr(Semconv.GEN_AI_AGENT_NAME)).isEqualTo("Editor");
        assertThat(out.get(1).statusCode()).isEqualTo(SpanRec.STATUS_OK);
    }

    // ── model calls (R4) ────────────────────────────────────────────────────────

    @Test
    void aModelCallIsAChatSpanWithItsNumbers() {
        feedAll(start(), modelCall(1.0, 0, "waited_ms", 100L, "attempts", 3, "failovers", List.of("Primary")));
        SpanRec chat = span(Semconv.KIND_MODEL);
        assertThat(chat.name()).isEqualTo("chat m1");
        assertThat(chat.attr(Semconv.GEN_AI_OPERATION_NAME)).isEqualTo("chat");
        assertThat(chat.attr(Semconv.GEN_AI_AGENT_NAME)).isEqualTo("Editor");
        assertThat(chat.attr(Semconv.GEN_AI_REQUEST_MODEL)).isEqualTo("m1");
        assertThat(chat.attr(Semconv.GEN_AI_RESPONSE_MODEL)).isEqualTo("m1");
        assertThat(chat.attr(Semconv.GEN_AI_USAGE_INPUT_TOKENS)).isEqualTo(100L);
        assertThat(chat.attr(Semconv.GEN_AI_USAGE_OUTPUT_TOKENS)).isEqualTo(20L);
        assertThat(chat.attr(Semconv.GEN_AI_RESPONSE_FINISH_REASONS)).isEqualTo("tool_calls");
        assertThat(chat.attr(Semconv.LOOM_MODEL_ATTEMPTS)).isEqualTo(3L);
        assertThat(chat.attr(Semconv.LOOM_MODEL_WAITED_MS)).isEqualTo(100L);
        assertThat(chat.attr(Semconv.LOOM_MODEL_FAILOVERS)).isEqualTo("Primary");
        assertThat(chat.attr(Semconv.LOOM_MODEL_TRUNCATED)).isEqualTo(false);
        assertThat(chat.statusCode()).isEqualTo(SpanRec.STATUS_OK);
        assertThat(chat.endNanos() - chat.startNanos()).isEqualTo(400_000_000L);
        assertThat(chat.kind()).isEqualTo(SpanRec.CLIENT);
    }

    @Test
    void aFailedModelCallIsAnErrorSpanWithItsCategory() {
        feedAll(start(), modelCall(1.0, 0, "outcome", "FAILED", "category", "RATE_LIMITED", "error_type", "RateLimitException", "model", null));
        SpanRec chat = span(Semconv.KIND_MODEL);
        assertThat(chat.statusCode()).isEqualTo(SpanRec.STATUS_ERROR);
        assertThat(chat.attr(Semconv.ERROR_TYPE)).isEqualTo("rate_limited");
        assertThat(chat.name()).isEqualTo("chat m1");
    }

    @Test
    void aTruncatedAnswerIsFlagged() {
        feedAll(start(), modelCall(1.0, 0, "finish_reason", "length", "truncated", true));
        assertThat(span(Semconv.KIND_MODEL).attr(Semconv.LOOM_MODEL_TRUNCATED)).isEqualTo(true);
    }

    @Test
    void spanIdsAreStableAcrossAReplayAndDifferPerCall() {
        feedAll(start(), modelCall(1.0, 0), modelCall(2.0, 1));
        List<SpanRec> chats = spans.stream().filter(s -> Semconv.KIND_MODEL.equals(s.attr(Semconv.LOOM_SPAN_KIND))).toList();
        assertThat(chats).hasSize(2);
        assertThat(chats.get(0).spanId()).isNotEqualTo(chats.get(1).spanId());
        List<SpanRec> again = new ArrayList<>();
        TraceMapper m = new TraceMapper("r-1", Detail.FULL);
        for (TraceEvent e : start()) m.map(e);
        again.addAll(m.map(modelCall(1.0, 0)).spans());
        assertThat(again.get(0).spanId()).isEqualTo(chats.get(0).spanId());
    }

    @Test
    void aBackwardsClockNeverMakesANegativeSpan() {
        feedAll(start(), modelCall(0.05, 0, "duration_ms", 5000L));
        SpanRec chat = span(Semconv.KIND_MODEL);
        assertThat(chat.endNanos()).isGreaterThanOrEqualTo(chat.startNanos());
    }

    // ── context, compaction and totals (R5, R6) ─────────────────────────────────

    @Test
    void contextBecomesALogRecordWithTypedNumbers() {
        feedAll(start(), ev(1.0, TraceEvent.CONTEXT, "Editor", "context 900 tokens of 2500 (36%)", "used", 900L, "source", "REPORTED", "window", 3000, "usable", 2500, "fraction", 0.36));
        LogRec r = logs.stream().filter(l -> Semconv.EV_CONTEXT.equals(l.eventName())).findFirst().orElseThrow();
        assertThat(r.attr(Semconv.LOOM_CONTEXT_USED)).isEqualTo(900L);
        assertThat(r.attr(Semconv.LOOM_CONTEXT_WINDOW)).isEqualTo(3000L);
        assertThat(r.attr(Semconv.LOOM_CONTEXT_USABLE)).isEqualTo(2500L);
        assertThat(r.attr(Semconv.LOOM_CONTEXT_FRACTION)).isEqualTo(0.36);
        assertThat(r.attr(Semconv.LOOM_CONTEXT_SOURCE)).isEqualTo("REPORTED");
        assertThat(r.attr(Semconv.GEN_AI_AGENT_NAME)).isEqualTo("Editor");
        assertThat(r.severityNumber()).isEqualTo(LogRec.INFO);
    }

    @Test
    void contextWithoutAWindowHasNoWindowAttributes() {
        feedAll(start(), ev(1.0, TraceEvent.CONTEXT, "Editor", "context 900 tokens", "used", 900L, "source", "ESTIMATED"));
        LogRec r = logs.stream().filter(l -> Semconv.EV_CONTEXT.equals(l.eventName())).findFirst().orElseThrow();
        assertThat(r.attr(Semconv.LOOM_CONTEXT_WINDOW)).isNull();
        assertThat(r.attr(Semconv.LOOM_CONTEXT_FRACTION)).isNull();
    }

    @Test
    void aCompactionBecomesALogRecordAndAFailedOneIsAWarning() {
        feedAll(start(),
                ev(1.0, TraceEvent.COMPACTION, "Editor", "drop_oldest compacted: 2100 to 900 tokens", "strategy", "drop_oldest", "outcome", "COMPACTED", "reason", "THRESHOLD",
                        "before", 2100L, "after", 900L, "messages_before", 20, "messages_after", 8, "summary_tokens", 300L, "summary_cost", "0.0012"),
                ev(2.0, TraceEvent.COMPACTION, "Editor", "x failed", "strategy", "x", "outcome", "FAILED", "reason", "THRESHOLD", "before", 2100L, "after", 2100L,
                        "messages_before", 20, "messages_after", 20, "error_type", "IllegalStateException"));
        List<LogRec> cs = logs.stream().filter(l -> Semconv.EV_COMPACTION.equals(l.eventName())).toList();
        assertThat(cs).hasSize(2);
        assertThat(cs.get(0).attr(Semconv.LOOM_COMPACTION_STRATEGY)).isEqualTo("drop_oldest");
        assertThat(cs.get(0).attr(Semconv.LOOM_COMPACTION_BEFORE)).isEqualTo(2100L);
        assertThat(cs.get(0).attr(Semconv.LOOM_COMPACTION_AFTER)).isEqualTo(900L);
        assertThat(cs.get(0).attr(Semconv.LOOM_COMPACTION_SUMMARY_TOKENS)).isEqualTo(300L);
        assertThat(cs.get(0).severityNumber()).isEqualTo(LogRec.INFO);
        assertThat(cs.get(1).severityNumber()).isEqualTo(LogRec.WARN);
        assertThat(cs.get(1).attr(Semconv.LOOM_COMPACTION_OUTCOME)).isEqualTo("FAILED");
    }

    @Test
    void runTotalsReachTheRunRecord() {
        feedAll(start(), ev(5, TraceEvent.RUN_END, null, "", "workflow", "Main", "run", "r-1", "status", "completed", "model_calls", 15L, "tool_calls", 14L,
                "tool_failures", 1L, "retries", 2L, "failovers", 1L, "compactions", 3L));
        LogRec end = logs.stream().filter(l -> Semconv.EV_RUN_END.equals(l.eventName())).findFirst().orElseThrow();
        assertThat(end.attr(Semconv.LOOM_RUN_MODEL_CALLS)).isEqualTo(15L);
        assertThat(end.attr(Semconv.LOOM_RUN_TOOL_CALLS)).isEqualTo(14L);
        assertThat(end.attr(Semconv.LOOM_RUN_TOOL_FAILURES)).isEqualTo(1L);
        assertThat(end.attr(Semconv.LOOM_RUN_RETRIES)).isEqualTo(2L);
        assertThat(end.attr(Semconv.LOOM_RUN_FAILOVERS)).isEqualTo(1L);
        assertThat(end.attr(Semconv.LOOM_RUN_COMPACTIONS)).isEqualTo(3L);
        SpanRec root = spans.stream().filter(s -> Semconv.KIND_WORKFLOW.equals(s.attr(Semconv.LOOM_SPAN_KIND))).findFirst().orElseThrow();
        assertThat(root.attr(Semconv.LOOM_RUN_MODEL_CALLS)).isEqualTo(15L);
    }

    // ── no text (R7) ────────────────────────────────────────────────────────────

    @Test
    void noNewRecordHoldsTextAtAnyDetailLevel() {
        String secret = "sk-SECRET-0123456789";
        for (Detail d : Detail.values()) {
            TraceMapper m = new TraceMapper("r-3", d);
            List<Object> all = new ArrayList<>();
            TraceEvent[] events = {
                    ev(0, TraceEvent.RUN_START, null, "", "workflow", "Main", "run", "r-3"),
                    ev(0.1, TraceEvent.DELEGATE_START, "Editor", "edit"),
                    ev(0.2, TraceEvent.CONTEXT, "Editor", "context 1 tokens", "used", 1L, "source", "REPORTED"),
                    modelCall(0.5, 0, "outcome", "FAILED", "category", "AUTHENTICATION", "error_type", "AuthenticationException"),
                    ev(0.6, TraceEvent.ACTION, "Editor", "Wiki {}", "tool", "Wiki"),
                    ev(0.7, TraceEvent.OBSERVATION, "Editor", "Error: 401 " + secret),
                    toolCall(0.71, "Wiki", "soft_failure", true, "category", "PERMISSION_DENIED"),
                    ev(0.8, TraceEvent.COMPACTION, "Editor", "drop_oldest compacted: 5 to 3 tokens", "strategy", "drop_oldest", "outcome", "COMPACTED", "reason", "THRESHOLD", "before", 5L, "after", 3L,
                            "messages_before", 2, "messages_after", 1)};
            for (TraceEvent e : events) {
                TraceMapper.Output o = m.map(e);
                boolean newType = List.of(TraceEvent.CONTEXT, TraceEvent.COMPACTION, TraceEvent.MODEL_CALL, TraceEvent.TOOL_CALL).contains(e.type());
                if (!newType) continue;
                o.logs().forEach(l -> all.add(l.body() + l.attributes()));
                // a tool span keeps the observation text at the detail level that allows it, as it always did; the new attributes on it are numbers and names
                o.spans().forEach(s -> {
                    if (Semconv.KIND_TOOL.equals(s.attr(Semconv.LOOM_SPAN_KIND))) {
                        s.attributes().forEach((k, v) -> { if (k.startsWith("loom.tool.")) all.add(k + v); });
                    } else {
                        all.add(s.name() + s.attributes() + s.statusMessage());
                    }
                });
            }
            // the tool span (held, then released by the tool_call) must not carry the secret either, except as the text the existing detail level allows
            assertThat(all.toString()).as("detail " + d).doesNotContain(secret);
        }
    }
}
