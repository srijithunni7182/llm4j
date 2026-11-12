package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.children.ChildHarness;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 34e, D16: the executor says, in its trace, what the console needs for the Children section:
 * each message sent or read (with the run on the other side), and each progress report. Messages
 * only: a latch is marked sticky so the console does not count it as unread.
 */
class SignalTraceTest {

    private static final String SOURCE =
            """
            workflow Child(x) progress { stage: string, done: number } {
                report { stage: "reading", done: 1 }
                wait signal "go" timeout 10m -> t
                notify parent "found" "data"
                return t
            }
            workflow Main(q) {
                start Child(x = q) budget { tokens: 10 } -> work
                notify work "go" "now"
                notify work "approved" "yes" sticky
                wait work signal "found" timeout 5m -> got
                wait work timeout 5m -> out
            }
            """;

    private List<TraceEvent> run() {
        ChildHarness h = new ChildHarness();
        h.parent(SOURCE, h.controlled()).executeWorkflow("Main", Map.of("q", "x"));
        return List.copyOf(h.traceEvents);
    }

    private static List<TraceEvent> of(List<TraceEvent> all, String type) {
        return all.stream().filter(e -> type.equals(e.type())).toList();
    }

    @Test
    void aReportIsTracedWithEachFieldUnderItsOwnKeyAndAReplayDoesNotTraceItAgain() {
        List<TraceEvent> progress = of(run(), TraceEvent.PROGRESS);

        assertThat(progress).hasSize(1);
        assertThat(progress.get(0).data()).containsEntry("f.stage", "reading").containsEntry("f.done", 1L);
    }

    @Test
    void everyMessageSentAndReadIsTracedWithThePeerAndALatchIsMarkedSticky() {
        List<TraceEvent> signals = of(run(), TraceEvent.SIGNAL);
        List<String> ops = signals.stream().map(e -> e.data().get("op") + ":" + e.data().get("name") + (Boolean.TRUE.equals(e.data().get("sticky")) ? ":sticky" : "")).toList();

        assertThat(ops).containsExactlyInAnyOrder("sent:go", "sent:approved:sticky", "read:go", "sent:found", "read:found");
        assertThat(signals).allSatisfy(e -> assertThat(String.valueOf(e.data().get("peer"))).isNotEmpty());
    }

    @Test
    void thePeerOfAMessageIsTheRunOnTheOtherSideSoTheConsoleCanPairASendWithItsRead() {
        List<TraceEvent> signals = of(run(), TraceEvent.SIGNAL);
        Object sentToChild = signals.stream().filter(e -> "sent".equals(e.data().get("op")) && "go".equals(e.data().get("name"))).findFirst().orElseThrow().data().get("peer");
        Object readByChild = signals.stream().filter(e -> "read".equals(e.data().get("op")) && "go".equals(e.data().get("name"))).findFirst().orElseThrow().data().get("peer");

        assertThat(sentToChild).as("the parent names the child").isNotEqualTo(readByChild);
        assertThat(String.valueOf(sentToChild)).startsWith(String.valueOf(readByChild)); // the child's id is derived from the parent's
    }

    @Test
    void theTextOfASignalIsNeverInTheTrace() {
        assertThat(run().stream().filter(e -> TraceEvent.SIGNAL.equals(e.type())).map(e -> String.valueOf(e.data()) + e.text()))
                .noneMatch(t -> t.contains("data") || t.contains("now"));
    }
}
