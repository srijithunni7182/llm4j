package io.github.llm4j.loom.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.context.ContextWindow;
import io.github.llm4j.context.ContextWindows;
import io.github.llm4j.loom.execution.TraceEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R10, R11: the trace shows context use, compaction, model calls and tool calls, as numbers and names. */
class ContextTraceEventsTest {

    @TempDir
    Path dir;

    private static WindowLayers tiny() {
        return WindowLayers.none().over("test", ContextWindows.empty().with("tiny-model", new ContextWindow(3000, 500)));
    }

    private TestLoom compacting() {
        TestLoom l = new TestLoom(dir, TestLoom.script("tiny-model", "context { compact: drop_oldest  keep_recent: 2  max_compactions: 50 }"), 14);
        l.executor.setContextWindows(tiny());
        l.run();
        return l;
    }

    private static List<TraceEvent> of(TestLoom l, String type) {
        synchronized (l.trace) {
            return l.trace.stream().filter(e -> e.type().equals(type)).toList();
        }
    }

    @Test
    void everyModelCallIsReportedWithItsNumbers() {
        TestLoom l = compacting();
        List<TraceEvent> calls = of(l, TraceEvent.MODEL_CALL);
        assertThat(calls).hasSize(15);
        Map<String, Object> d = calls.get(0).data();
        assertThat(calls.get(0).agent()).isEqualTo("Researcher");
        assertThat(d).containsKeys("model", "duration_ms", "attempts", "prompt_tokens", "completion_tokens", "outcome");
        assertThat(d.get("model")).isEqualTo("tiny-model");
        assertThat(d.get("outcome")).isEqualTo("OK");
        assertThat(((Number) d.get("attempts")).intValue()).isEqualTo(1);
        assertThat(calls.get(0).text()).contains("tiny-model");
    }

    @Test
    void everyToolCallIsReportedWithItsRealOutcome() {
        TestLoom l = compacting();
        List<TraceEvent> calls = of(l, TraceEvent.TOOL_CALL);
        assertThat(calls).hasSize(14);
        Map<String, Object> d = calls.get(0).data();
        assertThat(d.get("tool")).isEqualTo("lookup");
        assertThat(d.get("outcome")).isEqualTo("EXECUTED");
        assertThat(d.get("soft_failure")).isEqualTo(false);
        assertThat(((Number) d.get("result_chars")).intValue()).isPositive();
    }

    @Test
    void contextUseIsReportedOnEveryCallWithTheWindow() {
        TestLoom l = compacting();
        List<TraceEvent> use = of(l, TraceEvent.CONTEXT);
        assertThat(use).isNotEmpty();
        Map<String, Object> d = use.get(0).data();
        assertThat(((Number) d.get("window")).intValue()).isEqualTo(3000);
        assertThat(((Number) d.get("usable")).intValue()).isEqualTo(2500);
        assertThat(((Number) d.get("used")).longValue()).isPositive();
        assertThat(((Number) d.get("fraction")).doubleValue()).isGreaterThan(0.0);
        assertThat(d.get("source")).isIn("REPORTED", "ESTIMATED");
    }

    @Test
    void compactionsAreReported() {
        TestLoom l = compacting();
        List<TraceEvent> c = of(l, TraceEvent.COMPACTION);
        assertThat(c).isNotEmpty();
        Map<String, Object> d = c.stream().filter(e -> "COMPACTED".equals(e.data().get("outcome"))).findFirst().orElseThrow().data();
        assertThat(d.get("strategy")).isEqualTo("drop_oldest");
        assertThat(((Number) d.get("before")).longValue()).isGreaterThan(((Number) d.get("after")).longValue());
        assertThat(((Number) d.get("messages_before")).intValue()).isGreaterThan(((Number) d.get("messages_after")).intValue());
        assertThat(d).containsKey("reason");
    }

    @Test
    void anAgentWithoutABlockReportsCallsAndUseButNoWindowAndNoCompaction() {
        TestLoom l = new TestLoom(dir, TestLoom.script("tiny-model", ""), 3);
        l.run();
        assertThat(of(l, TraceEvent.MODEL_CALL)).hasSize(4);
        assertThat(of(l, TraceEvent.TOOL_CALL)).hasSize(3);
        assertThat(of(l, TraceEvent.CONTEXT)).isNotEmpty().allSatisfy(e -> assertThat(e.data()).containsKey("used").doesNotContainKeys("window", "usable", "fraction"));
        assertThat(of(l, TraceEvent.COMPACTION)).isEmpty();
    }

    @Test
    void noEventsAreBuiltWhenNobodyListens() {
        TestLoom l = new TestLoom(dir, TestLoom.script("tiny-model", "context { compact: drop_oldest }"), 3, false);
        l.executor.setContextWindows(tiny());
        l.run();
        assertThat(l.trace).isEmpty();
        assertThat(l.executor.stats().totalModelCalls()).as("statistics are kept regardless").isEqualTo(4);
    }

    @Test
    void theNewEventsHoldNoPromptAnswerOrResultText() {
        TestLoom l = compacting();
        for (String type : List.of(TraceEvent.MODEL_CALL, TraceEvent.TOOL_CALL, TraceEvent.CONTEXT, TraceEvent.COMPACTION)) {
            for (TraceEvent e : of(l, type)) {
                String all = e.text() + e.data();
                assertThat(all).doesNotContain("data data").doesNotContain("research the topic").doesNotContain("finished").doesNotContain("topic 1");
            }
        }
    }

    @Test
    void aFailedModelCallIsReportedWithItsCategory() {
        TestLoom l = new TestLoom(dir, TestLoom.script("tiny-model", ""), 3);
        l.failWith = new io.github.llm4j.exception.AuthenticationException("bad key");
        try {
            l.run();
        } catch (RuntimeException expected) {
            // the run fails as it always did
        }
        List<TraceEvent> calls = of(l, TraceEvent.MODEL_CALL);
        assertThat(calls).isNotEmpty();
        assertThat(calls.get(0).data().get("outcome")).isEqualTo("FAILED");
        assertThat(calls.get(0).data().get("category")).isEqualTo("AUTHENTICATION");
    }

    @Test
    void aToolThatReturnsAnErrorIsASoftFailureWhileTheObservationIsUnchanged() {
        TestLoom l = new TestLoom(dir, TestLoom.script("tiny-model", ""), 2);
        l.tools.register("lookup", new io.github.llm4j.agent.Tool() {
            @Override public String getName() { return "lookup"; }
            @Override public String getDescription() { return "fails softly"; }
            @Override public String execute(Map<String, Object> args) { return "Error: 404 not found for " + args.get("q"); }
        });
        l.run();
        List<TraceEvent> calls = of(l, TraceEvent.TOOL_CALL);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).data().get("soft_failure")).isEqualTo(true);
        assertThat(calls.get(0).data().get("category")).isEqualTo("NOT_FOUND");
        assertThat(of(l, TraceEvent.OBSERVATION)).isNotEmpty();
    }

    @Test
    void eventsKeepTheirOrderWithinAnAgent() {
        TestLoom l = new TestLoom(dir, TestLoom.script("tiny-model", ""), 2);
        l.run();
        List<String> kinds;
        synchronized (l.trace) {
            kinds = l.trace.stream().map(TraceEvent::type).filter(t -> t.equals(TraceEvent.MODEL_CALL) || t.equals(TraceEvent.TOOL_CALL)).toList();
        }
        assertThat(kinds).containsExactly("model_call", "tool_call", "model_call", "tool_call", "model_call");
    }

    @Test
    void aSecretInAToolResultOrAnErrorNeverReachesTheNewEvents() {
        String secret = "sk-SECRET-0123456789";
        TestLoom l = new TestLoom(dir, TestLoom.script("tiny-model", "context { compact: drop_oldest }"), 3);
        l.tools.register("lookup", new io.github.llm4j.agent.Tool() {
            @Override public String getName() { return "lookup"; }
            @Override public String getDescription() { return "leaky"; }
            @Override public String execute(Map<String, Object> args) { return "Error: 401 unauthorized, key " + secret; }
        });
        l.executor.setContextWindows(tiny());
        l.run();
        for (String type : List.of(TraceEvent.MODEL_CALL, TraceEvent.TOOL_CALL, TraceEvent.CONTEXT, TraceEvent.COMPACTION)) {
            for (TraceEvent e : of(l, type)) assertThat(e.text() + e.data()).doesNotContain(secret);
        }
    }
}
