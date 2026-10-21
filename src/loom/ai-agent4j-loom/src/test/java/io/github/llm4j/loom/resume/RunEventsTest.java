package io.github.llm4j.loom.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code run_start} and {@code run_end}: a run's edges are in the trace, with how it ended. */
class RunEventsTest {

    private static final String AGENTS =
            """
            agent Researcher { model: "test/model" system: "You are Researcher." }
            agent Drafter { model: "test/model" system: "You are Drafter." }
            """;

    @TempDir Path dir;

    private final TestClock clock = new TestClock();
    private final List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());

    private LimitScript script(String source, RunJournal journal, LimitScript.Gate gate) {
        return new LimitScript(source, journal, clock, gate, e -> e.addTraceListener(events::add));
    }

    private List<TraceEvent> edges() {
        return events.stream()
                .filter(
                        e ->
                                TraceEvent.RUN_START.equals(e.type())
                                        || TraceEvent.RUN_END.equals(e.type()))
                .toList();
    }

    @Test
    void aCompletedRunHasOneStartAndOneEndSaying_completed() {
        script(
                        AGENTS + "workflow Main() { delegate \"x\" to Researcher -> r }",
                        RunJournal.inMemory(),
                        null)
                .run();
        assertThat(edges())
                .extracting(TraceEvent::type)
                .containsExactly(TraceEvent.RUN_START, TraceEvent.RUN_END);
        assertThat(edges().get(0).data())
                .containsEntry("workflow", "Main")
                .containsEntry("resumed", "false");
        assertThat(edges().get(1).data())
                .containsEntry("status", "completed")
                .containsEntry("workflow", "Main");
        assertThat(events.get(0).type()).isEqualTo(TraceEvent.RUN_START);
        assertThat(events.get(events.size() - 1).type()).isEqualTo(TraceEvent.RUN_END);
    }

    @Test
    void aSleepingRunEndsAsSuspendedWithTheReasonAndWakeTime_andIsMarkedResumedLater() {
        String source =
                AGENTS + "workflow Main() { sleep 90s\n delegate \"x\" to Researcher -> r }";
        RunJournal journal = new FileRunJournal(dir.resolve("journal.json"));
        assertThatThrownBy(script(source, journal, null)::run).isInstanceOf(RunSuspended.class);
        TraceEvent end = edges().get(1);
        assertThat(end.data())
                .containsEntry("status", "suspended")
                .containsEntry("reason", "SLEEP")
                .containsEntry("resumeAt", TestClock.T0.plusSeconds(90).toString());
        int suspendedAt =
                events.indexOf(
                        events.stream()
                                .filter(e -> TraceEvent.SUSPENDED.equals(e.type()))
                                .findFirst()
                                .orElseThrow());
        assertThat(suspendedAt).isLessThan(events.indexOf(end));

        events.clear();
        clock.advance(Duration.ofSeconds(90));
        script(source, new FileRunJournal(dir.resolve("journal.json")), null).run();
        assertThat(edges().get(0).data()).containsEntry("resumed", "true");
        assertThat(edges().get(1).data()).containsEntry("status", "completed");
    }

    @Test
    void aFailedRunEndsAsFailedWithTheError() {
        String source =
                AGENTS
                        + "rate_limits { on_limit: fail }\nworkflow Main() { delegate \"x\" to Drafter -> d }";
        LimitScript run =
                script(
                        source,
                        RunJournal.inMemory(),
                        LimitScript.limitedUntil(clock, "Drafter", TestClock.T0.plusSeconds(3600)));
        assertThatThrownBy(run::run).hasMessageContaining("rate limit");
        TraceEvent end = edges().get(1);
        assertThat(end.data()).containsEntry("status", "failed");
        assertThat(String.valueOf(end.data().get("error"))).contains("rate limit");
    }
}
