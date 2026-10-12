package io.github.llm4j.loom.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code sleep}: a durable run pauses and is woken at the recorded time; otherwise it waits in place. All on a fake clock. */
class SleepTest {

    private static final String AGENTS = """
            agent Researcher { model: "test/model" system: "You are Researcher." }
            agent Drafter { model: "test/model" system: "You are Drafter." }
            """;

    private static final String SLEEPS = AGENTS + """
            workflow Main() {
                delegate "research" to Researcher -> research
                sleep 90s
                delegate "draft {research}" to Drafter -> draft
            }
            """;

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock();

    private RunJournal durable() {
        return new FileRunJournal(dir.resolve("journal.json"));
    }

    private LimitScript script(String source, RunJournal journal) {
        return new LimitScript(source, journal, clock, null);
    }

    private static Map<?, ?> suspension(RunJournal journal) {
        return (Map<?, ?>) journal.get(HarnessExecutor.SUSPENSION).orElseThrow().value();
    }

    @Test
    void aDurableRunPausesAtTheSleepAndHoldsNoThread() {
        RunJournal journal = durable();
        LimitScript first = script(SLEEPS, journal);
        assertThatThrownBy(first::run).isInstanceOfSatisfying(RunSuspended.class, s -> {
            assertThat(s.reason()).isEqualTo(RunSuspended.Reason.SLEEP);
            assertThat(s.resumeAt()).isEqualTo(TestClock.T0.plusSeconds(90));
            assertThat(s.stepId()).isEqualTo("Main/s1");
            assertThat(s.detail()).isEqualTo("sleeping 90s");
        });
        assertThat(first.slept).isEmpty();
        assertThat(first.calls("Researcher")).isEqualTo(1);
        assertThat(first.calls("Drafter")).isZero();
        assertThat(journal.get("Main/s1").orElseThrow().kind()).isEqualTo("sleep");
        Map<?, ?> rec = suspension(journal);
        assertThat(rec.get("reason")).isEqualTo("SLEEP");
        assertThat(rec.get("resumeAt")).isEqualTo("2026-09-27T10:01:30Z");
        assertThat(rec.get("detail")).isEqualTo("sleeping 90s");
    }

    @Test
    void resumingAfterTheWakeTimeReplaysEarlierStepsAndRunsTheRest() {
        RunJournal journal = durable();
        assertThatThrownBy(script(SLEEPS, journal)::run).isInstanceOf(RunSuspended.class);

        clock.set(TestClock.T0.plusSeconds(90));
        LimitScript resumed = script(SLEEPS, new FileRunJournal(dir.resolve("journal.json"))).run();
        assertThat(resumed.calls("Researcher")).isZero();
        assertThat(resumed.calls("Drafter")).isEqualTo(1);
        assertThat(resumed.slept).isEmpty();
    }

    @Test
    void anEarlyResumeKeepsTheOriginalWakeTimeAndPausesAgain() {
        RunJournal journal = durable();
        assertThatThrownBy(script(SLEEPS, journal)::run).isInstanceOf(RunSuspended.class);

        clock.advance(Duration.ofSeconds(40)); // woken by hand, too soon
        assertThatThrownBy(script(SLEEPS, new FileRunJournal(dir.resolve("journal.json")))::run)
                .isInstanceOfSatisfying(RunSuspended.class,
                        s -> assertThat(s.resumeAt()).isEqualTo(TestClock.T0.plusSeconds(90)));
    }

    @Test
    void aSleepIsNotCountedAgainstMaxResumes() {
        String twice = AGENTS + """
                rate_limits { max_resumes: 1 }
                workflow Main() {
                    sleep 60s
                    sleep 60s
                    delegate "draft" to Drafter -> draft
                }
                """;
        RunJournal journal = durable();
        assertThatThrownBy(script(twice, journal)::run).isInstanceOf(RunSuspended.class);
        clock.advance(Duration.ofSeconds(60));
        assertThatThrownBy(script(twice, new FileRunJournal(dir.resolve("journal.json")))::run).isInstanceOf(RunSuspended.class);
        clock.advance(Duration.ofSeconds(60));
        LimitScript done = script(twice, new FileRunJournal(dir.resolve("journal.json"))).run();
        assertThat(done.calls("Drafter")).isEqualTo(1);
        assertThat(done.executor.getResumes()).isZero();
    }

    @Test
    void withoutADurableJournalTheSleepIsWaitedInPlace() {
        LimitScript run = script(SLEEPS, RunJournal.inMemory()).run();
        assertThat(run.slept).containsExactly(Duration.ofSeconds(90));
        assertThat(run.calls("Drafter")).isEqualTo(1);
    }

    @Test
    void aShortSleepIsWaitedInPlaceEvenWithADurableJournal() {
        String shortSleep = AGENTS + """
                workflow Main() {
                    sleep 20s
                    delegate "draft" to Drafter -> draft
                }
                """;
        LimitScript run = script(shortSleep, durable()).run();
        assertThat(run.slept).containsExactly(Duration.ofSeconds(20));
        assertThat(run.calls("Drafter")).isEqualTo(1);
    }

    @Test
    void eachRoundOfALoopSleepsUntilItsOwnWakeTime() {
        String loop = AGENTS + """
                workflow Main() {
                    loop until (r == "never") max 2 {
                        delegate "round" to Researcher -> r
                        sleep 60s
                    }
                }
                """;
        RunJournal journal = durable();
        assertThatThrownBy(script(loop, journal)::run).isInstanceOfSatisfying(RunSuspended.class,
                s -> assertThat(s.resumeAt()).isEqualTo(TestClock.T0.plusSeconds(60)));
        clock.set(TestClock.T0.plusSeconds(60));
        assertThatThrownBy(script(loop, new FileRunJournal(dir.resolve("journal.json")))::run).isInstanceOfSatisfying(RunSuspended.class,
                s -> assertThat(s.resumeAt()).isEqualTo(TestClock.T0.plusSeconds(120)));
    }

    @Test
    void aSleepInOneBranchLetsTheOtherBranchFinishFirst() {
        String parallel = AGENTS + """
                workflow Main() {
                    parallel {
                        sleep 5m
                        delegate "research" to Researcher -> research
                    }
                    delegate "draft" to Drafter -> draft
                }
                """;
        RunJournal journal = durable();
        LimitScript first = script(parallel, journal);
        assertThatThrownBy(first::run).isInstanceOfSatisfying(RunSuspended.class, s -> {
            assertThat(s.reason()).isEqualTo(RunSuspended.Reason.SLEEP);
            assertThat(s.resumeAt()).isEqualTo(TestClock.T0.plus(Duration.ofMinutes(5)));
        });
        assertThat(first.calls("Researcher")).isEqualTo(1);
        assertThat(first.calls("Drafter")).isZero();
    }

    @Test
    void theTraceShowsTheSleepStartingAndEnding() {
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        RunJournal journal = durable();
        LimitScript first = new LimitScript(SLEEPS, journal, clock, null, e -> e.addTraceListener(events::add));
        assertThatThrownBy(first::run).isInstanceOf(RunSuspended.class);
        assertThat(sleepStates(events)).containsExactly("start");
        TraceEvent start = events.stream().filter(e -> TraceEvent.SLEEP.equals(e.type())).findFirst().orElseThrow();
        assertThat(start.data()).containsEntry("wakeAt", TestClock.T0.plusSeconds(90).toString()).containsEntry("millis", "90000");

        events.clear();
        clock.set(TestClock.T0.plusSeconds(90));
        new LimitScript(SLEEPS, new FileRunJournal(dir.resolve("journal.json")), clock, null, e -> e.addTraceListener(events::add)).run();
        assertThat(sleepStates(events)).containsExactly("end");
    }

    private static List<String> sleepStates(List<TraceEvent> events) {
        return events.stream().filter(e -> TraceEvent.SLEEP.equals(e.type())).map(e -> String.valueOf(e.data().get("state"))).toList();
    }

    @Test
    void theWakeTimeIsAnInstantInTheJournal() {
        RunJournal journal = RunJournal.inMemory();
        script(SLEEPS, journal).run();
        Object recorded = journal.get("Main/s1").orElseThrow().value();
        assertThat(Instant.parse(String.valueOf(recorded))).isEqualTo(TestClock.T0.plusSeconds(90));
    }
}
