package io.github.llm4j.loom.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.ratelimit.RateLimitInfo;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan V6.1–V6.15. */
class SuspensionTest {

    static final String AGENTS = """
            agent Researcher { model: "test/model" system: "You are Researcher." }
            agent Drafter { model: "test/model" system: "You are Drafter." }
            agent Reviewer { model: "test/model" system: "You are Reviewer." }
            """;

    static final String THREE_STEPS = AGENTS + """
            workflow Main() {
                delegate "research" to Researcher -> research
                delegate "draft {research}" to Drafter -> draft
                delegate "review {draft}" to Reviewer -> review
            }
            """;

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock();

    private RunJournal durable() {
        return new FileRunJournal(dir.resolve("journal.json"));
    }

    private static Map<?, ?> suspension(RunJournal journal) {
        return (Map<?, ?>) journal.get(HarnessExecutor.SUSPENSION).orElseThrow().value();
    }

    @Test
    void v6_1_aDurableRunPausesUntilTheReset() {
        RunJournal journal = durable();
        Instant reset = Instant.parse("2026-09-27T11:00:00Z");
        LimitScript first = new LimitScript(THREE_STEPS, journal, clock, LimitScript.limitedUntil(clock, "Drafter", reset));
        assertThatThrownBy(first::run).isInstanceOfSatisfying(RunSuspended.class, s -> {
            assertThat(s.reason()).isEqualTo(RunSuspended.Reason.RATE_LIMIT);
            assertThat(s.resumeAt()).isEqualTo(reset);
            assertThat(s.stepId()).isEqualTo("Main/s1");
            assertThat(s.limit().provider()).isEqualTo("test");
        });
        assertThat(journal.get("Main/s0").orElseThrow().value()).isEqualTo("Researcher#1");
        assertThat(journal.get("Main/s1")).isEmpty();
        Map<?, ?> rec = suspension(journal);
        assertThat(rec.get("state")).isEqualTo("suspended");
        assertThat(rec.get("step")).isEqualTo("Main/s1");
        assertThat(rec.get("resumeAt")).isEqualTo("2026-09-27T11:00:00Z");
        assertThat(first.slept).isEmpty();
        assertThat(first.calls("Reviewer")).isZero();
    }

    @Test
    void v6_2_resumingRunsOnlyTheRestOfTheRun() {
        RunJournal journal = durable();
        Instant reset = Instant.parse("2026-09-27T11:00:00Z");
        LimitScript.Gate gate = LimitScript.limitedUntil(clock, "Drafter", reset);
        assertThatThrownBy(new LimitScript(THREE_STEPS, journal, clock, gate)::run).isInstanceOf(RunSuspended.class);

        clock.set(reset);
        LimitScript resumed = new LimitScript(THREE_STEPS, new FileRunJournal(dir.resolve("journal.json")), clock, gate).run();
        assertThat(resumed.calls("Researcher")).isZero(); // replayed
        assertThat(resumed.calls("Drafter")).isEqualTo(1);
        assertThat(resumed.calls("Reviewer")).isEqualTo(1);
        assertThat(resumed.var("research")).isEqualTo("Researcher#1");
        assertThat(resumed.var("draft")).isEqualTo("Drafter#1");
        assertThat(resumed.var("review")).isEqualTo("Reviewer#1");
        assertThat(resumed.executor.getResumes()).isEqualTo(1);
        assertThat(suspension(resumed.journal).get("state")).isEqualTo("resumed");
    }

    @Test
    void v6_3_withoutADurableJournalAShortResetIsWaitedInline() {
        Instant reset = TestClock.T0.plusSeconds(20);
        LimitScript run = new LimitScript(THREE_STEPS, RunJournal.inMemory(), clock,
                LimitScript.limitedUntil(clock, "Drafter", reset)).run();
        assertThat(run.slept).containsExactly(Duration.ofSeconds(20));
        assertThat(run.var("review")).isEqualTo("Reviewer#1");
        assertThat(run.calls("Drafter")).isEqualTo(1);
        assertThat(run.audited("rate_limit_wait")).isEqualTo(1);
    }

    @Test
    void v6_4_withoutADurableJournalALongResetFailsTheRun() {
        LimitScript run = new LimitScript(THREE_STEPS, RunJournal.inMemory(), clock,
                LimitScript.limitedUntil(clock, "Drafter", Instant.parse("2026-09-27T11:00:00Z")));
        assertThatThrownBy(run::run).hasMessageContaining("rate limit").hasMessageContaining("11:00")
                .hasMessageContaining("max_wait 5m");
        assertThat(run.slept).isEmpty();
    }

    @Test
    void v6_5_onLimitFailRunsOnFailure() {
        String source = AGENTS + """
                rate_limits { on_limit: fail }
                workflow Main() {
                    delegate "draft" to Drafter -> draft on_failure {
                        delegate "fallback: {_error}" to Reviewer -> draft
                    }
                }
                """;
        RunJournal journal = durable();
        LimitScript run = new LimitScript(source, journal, clock,
                LimitScript.limitedUntil(clock, "Drafter", TestClock.T0.plusSeconds(10))).run();
        assertThat(run.var("draft")).isEqualTo("Reviewer#1");
        assertThat(run.tasks).anySatisfy(t -> assertThat(t).contains("fallback: rate limited: test request rate limit"));
        assertThat(journal.get(HarnessExecutor.SUSPENSION)).isEmpty();
        assertThat(run.slept).isEmpty();
    }

    @Test
    void v6_6_aResetBeyondMaxWaitFailsInsteadOfSuspending() {
        String source = "rate_limits { max_wait: 1h }\n" + THREE_STEPS;
        LimitScript run = new LimitScript(source, durable(), clock,
                LimitScript.limitedUntil(clock, "Drafter", TestClock.T0.plus(Duration.ofHours(2))));
        assertThatThrownBy(run::run).isNotInstanceOf(RunSuspended.class).hasMessageContaining("max_wait 1h");
    }

    @Test
    void v6_7_parallelBranchesFinishThenTheRunPausesOnceAtTheLatestReset() {
        String source = AGENTS + """
                workflow Main() {
                    parallel {
                        delegate "a" to Researcher -> a
                        delegate "b" to Drafter -> b
                        delegate "c" to Reviewer -> c
                    }
                }
                """;
        RunJournal journal = durable();
        Instant b = Instant.parse("2026-09-27T10:30:00Z");
        Instant c = Instant.parse("2026-09-27T10:45:00Z");
        LimitScript.Gate gate = (agent, task) -> {
            if (agent.equals("Drafter") && clock.instant().isBefore(b)) return RateLimitInfo.at(b, null, "x", "b");
            if (agent.equals("Reviewer") && clock.instant().isBefore(c)) return RateLimitInfo.at(c, null, "x", "c");
            return null;
        };
        LimitScript first = new LimitScript(source, journal, clock, gate);
        assertThatThrownBy(first::run).isInstanceOfSatisfying(RunSuspended.class, s -> assertThat(s.resumeAt()).isEqualTo(c));
        assertThat(journal.get("Main/s0/p0").orElseThrow().value()).isEqualTo("Researcher#1");

        clock.set(c);
        LimitScript resumed = new LimitScript(source, journal, clock, gate).run();
        assertThat(resumed.calls("Researcher")).isZero();
        assertThat(resumed.calls("Drafter")).isEqualTo(1);
        assertThat(resumed.calls("Reviewer")).isEqualTo(1);
        assertThat(resumed.var("a")).isEqualTo("Researcher#1");
    }

    @Test
    void v6_7b_aFailingBranchWinsOverAPausedOne() {
        String source = AGENTS + """
                rate_limits { max_wait: 1h }
                workflow Main() {
                    parallel {
                        delegate "b" to Drafter -> b
                        delegate "c" to Reviewer -> c
                    }
                }
                """;
        LimitScript.Gate gate = (agent, task) -> agent.equals("Drafter")
                ? RateLimitInfo.at(TestClock.T0.plusSeconds(600), null, "x", "b")
                : agent.equals("Reviewer") ? RateLimitInfo.at(TestClock.T0.plus(Duration.ofDays(1)), null, "x", "c") : null;
        assertThatThrownBy(new LimitScript(source, durable(), clock, gate)::run)
                .isNotInstanceOf(RunSuspended.class).hasMessageContaining("max_wait");
    }

    @Test
    void v6_8_parallelForEachJournalsEveryItemThatCouldRun() {
        String source = AGENTS + """
                workflow Main() {
                    parallel for each item in items {
                        delegate "write {item}" to Drafter -> {item}
                    }
                }
                """;
        RunJournal journal = durable();
        Instant reset = Instant.parse("2026-09-27T10:05:00Z");
        LimitScript.Gate gate = (agent, task) -> task.contains("write i3") && clock.instant().isBefore(reset)
                ? RateLimitInfo.at(reset, null, "x", "i3") : null;
        LimitScript first = new LimitScript(source, journal, clock, gate);
        first.executor.getContext().setVariable("items", java.util.List.of("i1", "i2", "i3", "i4", "i5"));
        assertThatThrownBy(first::run).isInstanceOf(RunSuspended.class);
        assertThat(first.calls("Drafter")).isEqualTo(4);
        for (int i : new int[] {0, 1, 3, 4}) assertThat(journal.get("Main/s0/e" + i + "/b0")).isPresent();
        assertThat(journal.get("Main/s0/e2/b0")).isEmpty();

        clock.set(reset);
        LimitScript resumed = new LimitScript(source, journal, clock, gate);
        resumed.executor.getContext().setVariable("items", java.util.List.of("i1", "i2", "i3", "i4", "i5"));
        resumed.run();
        assertThat(resumed.calls("Drafter")).isEqualTo(1);
        assertThat(resumed.var("i3")).isEqualTo("Drafter#1");
    }

    @Test
    void v6_8b_sequentialForEachStopsAtTheLimitedItem() {
        String source = AGENTS + """
                workflow Main() {
                    for each item in items {
                        delegate "write {item}" to Drafter -> {item}
                    }
                }
                """;
        RunJournal journal = durable();
        Instant reset = Instant.parse("2026-09-27T10:05:00Z");
        LimitScript.Gate gate = (agent, task) -> task.contains("write i3") && clock.instant().isBefore(reset)
                ? RateLimitInfo.at(reset, null, "x", "i3") : null;
        LimitScript first = new LimitScript(source, journal, clock, gate);
        first.executor.getContext().setVariable("items", java.util.List.of("i1", "i2", "i3", "i4", "i5"));
        assertThatThrownBy(first::run).isInstanceOf(RunSuspended.class);
        assertThat(first.calls("Drafter")).isEqualTo(2);

        clock.set(reset);
        LimitScript resumed = new LimitScript(source, journal, clock, gate);
        resumed.executor.getContext().setVariable("items", java.util.List.of("i1", "i2", "i3", "i4", "i5"));
        resumed.run();
        assertThat(resumed.calls("Drafter")).isEqualTo(3);
    }

    @Test
    void v6_9_maxResumesGivesUp() {
        String source = "rate_limits { max_resumes: 2 }\n" + THREE_STEPS;
        RunJournal journal = durable();
        // A limit that never lifts: every attempt is told to come back in an hour.
        LimitScript.Gate gate = (agent, task) -> agent.equals("Drafter")
                ? RateLimitInfo.at(clock.instant().plus(Duration.ofHours(1)), null, "x", "never") : null;
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(new LimitScript(source, journal, clock, gate)::run).isInstanceOf(RunSuspended.class);
            clock.advance(Duration.ofHours(1));
        }
        assertThatThrownBy(new LimitScript(source, journal, clock, gate)::run)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("max_resumes 2");
        assertThat(suspension(journal).get("state")).isEqualTo("failed");
    }

    @Test
    void v6_10_runVariables() {
        String source = AGENTS + """
                workflow Main() {
                    delegate "draft" to Drafter -> draft
                    delegate "resumes={_run.resumes} reason={_run.lastSuspension.reason}" to Reviewer -> review
                }
                """;
        RunJournal journal = durable();
        Instant r1 = Instant.parse("2026-09-27T10:30:00Z");
        Instant r2 = Instant.parse("2026-09-27T10:45:00Z");
        LimitScript.Gate gate = (agent, task) -> !agent.equals("Drafter") ? null
                : clock.instant().isBefore(r1) ? RateLimitInfo.at(r1, null, "x", "1")
                : clock.instant().isBefore(r2) ? RateLimitInfo.at(r2, null, "x", "2") : null;
        assertThatThrownBy(new LimitScript(source, journal, clock, gate)::run).isInstanceOf(RunSuspended.class);
        clock.set(r1);
        assertThatThrownBy(new LimitScript(source, journal, clock, gate)::run).isInstanceOf(RunSuspended.class);
        clock.set(r2);
        LimitScript done = new LimitScript(source, journal, clock, gate).run();
        assertThat(done.tasks).anySatisfy(t -> assertThat(t).contains("resumes=2 reason=RATE_LIMIT"));
    }

    @Test
    void v6_11_auditRecordsEachPauseAndResume() {
        RunJournal journal = durable();
        Instant reset = Instant.parse("2026-09-27T11:00:00Z");
        LimitScript.Gate gate = LimitScript.limitedUntil(clock, "Drafter", reset);
        LimitScript first = new LimitScript(THREE_STEPS, journal, clock, gate);
        assertThatThrownBy(first::run).isInstanceOf(RunSuspended.class);
        assertThat(first.audited("run_suspended")).isEqualTo(1);
        assertThat(first.audited("run_resumed")).isZero();
        Map<String, Object> paused = first.auditData.get(first.audit.indexOf("run_suspended"));
        assertThat(paused).containsEntry("step", "Main/s1").containsEntry("reason", "RATE_LIMIT")
                .containsEntry("resumeAt", "2026-09-27T11:00:00Z").containsEntry("provider", "test")
                .containsEntry("estimated", "false");

        clock.set(reset);
        LimitScript resumed = new LimitScript(THREE_STEPS, journal, clock, gate).run();
        assertThat(resumed.audited("run_resumed")).isEqualTo(1);
        assertThat(resumed.audited("run_suspended")).isZero();
        Map<String, Object> back = resumed.auditData.get(resumed.audit.indexOf("run_resumed"));
        assertThat(back).containsEntry("attempt", "1").containsEntry("waitedMillis", String.valueOf(3_600_000));
    }

    @Test
    void v6_12_aWindowedBudgetPausesUntilItRefills() {
        String source = """
                budget { tokens: 500 per hour when_exhausted: suspend }
                agent Writer { model: "test/model" system: "You are Writer." budget { per_call: 50 } }
                workflow Main() {
                    delegate "one" to Writer -> first
                    delegate "two" to Writer -> second
                    delegate "three" to Writer -> third
                    delegate "four" to Writer -> fourth
                }
                """;
        RunJournal journal = durable();
        clock.set("2026-09-27T10:20:00Z");
        LimitScript first = new LimitScript(source, journal, clock, null);
        assertThatThrownBy(first::run).isInstanceOfSatisfying(RunSuspended.class, s -> {
            assertThat(s.reason()).isEqualTo(RunSuspended.Reason.BUDGET_WINDOW);
            assertThat(s.resumeAt()).isEqualTo(Instant.parse("2026-09-27T11:00:00Z"));
            assertThat(s.limit().provider()).isEqualTo("budget:run");
        });
        assertThat(first.calls("Writer")).isEqualTo(3);

        clock.set("2026-09-27T11:00:00Z");
        LimitScript resumed = new LimitScript(source, journal, clock, null).run();
        assertThat(resumed.calls("Writer")).isEqualTo(1);
        assertThat(resumed.var("fourth")).isEqualTo("Writer#1");
        assertThat(resumed.executor.getRunBudget().spent().tokens()).isEqualTo(150); // this hour only
        assertThat(resumed.executor.getRunBudget().lifetimeSpent().tokens()).isEqualTo(600);
    }

    @Test
    void v6_13_askForMore() {
        String source = """
                budget { tokens: 500 when_exhausted: ask }
                agent Writer { model: "test/model" system: "You are Writer." budget { per_call: 50 } }
                workflow Main() {
                    delegate "one" to Writer -> first
                    delegate "two" to Writer -> second
                    delegate "three" to Writer -> third
                    delegate "four" to Writer -> fourth
                }
                """;
        RunJournal journal = durable();
        AtomicInteger asked = new AtomicInteger();
        HumanInterface later = new HumanInterface() {
            @Override
            public String promptHuman(String message) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String promptHuman(String stepId, String message) {
                asked.incrementAndGet();
                assertThat(message).contains("Budget run is used up").contains("Allow 500 tokens more?");
                throw new RunSuspended(stepId, message);
            }
        };
        LimitScript first = new LimitScript(source, journal, clock, null, e -> e.setHumanInterface(later));
        String[] key = new String[1];
        assertThatThrownBy(first::run).isInstanceOfSatisfying(RunSuspended.class, s -> {
            assertThat(s.reason()).isEqualTo(RunSuspended.Reason.HUMAN);
            assertThat(s.resumeAt()).isNull();
            key[0] = s.stepId();
        });
        assertThat(key[0]).isEqualTo("Main/s3#more:run:1");
        assertThat(journal.get(HarnessExecutor.SUSPENSION)).isEmpty(); // a human pause, not a limit

        journal.answer(key[0], "yes");
        LimitScript resumed = new LimitScript(source, journal, clock, null, e -> e.setHumanInterface(later)).run();
        assertThat(asked.get()).isEqualTo(1);
        assertThat(resumed.executor.getRunBudget().limits().tokens()).isEqualTo(1000);
        assertThat(resumed.var("fourth")).isEqualTo("Writer#1");
    }

    @Test
    void v6_13b_askAnsweredNoStopsAsUsual() {
        String source = """
                budget { tokens: 200 when_exhausted: ask }
                agent Writer { model: "test/model" system: "You are Writer." budget { per_call: 50 } }
                workflow Main() {
                    delegate "one" to Writer -> first
                    delegate "two" to Writer -> second
                }
                """;
        HumanInterface no = message -> "no";
        LimitScript run = new LimitScript(source, durable(), clock, null, e -> e.setHumanInterface(no));
        assertThatThrownBy(run::run).isInstanceOf(io.github.llm4j.budget.BudgetExceeded.class);
        // and with nobody to ask, it stops too
        LimitScript alone = new LimitScript(source, durable(), clock, null);
        assertThatThrownBy(alone::run).isInstanceOf(io.github.llm4j.budget.BudgetExceeded.class);
    }

    @Test
    void v6_15_spendCoversEveryAttemptOnce() {
        String source = "budget { tokens: 100000 }\n" + THREE_STEPS;
        RunJournal journal = durable();
        Instant r1 = Instant.parse("2026-09-27T10:30:00Z");
        Instant r2 = Instant.parse("2026-09-27T10:45:00Z");
        LimitScript.Gate gate = (agent, task) -> !agent.equals("Drafter") ? null
                : clock.instant().isBefore(r1) ? RateLimitInfo.at(r1, null, "x", "1")
                : clock.instant().isBefore(r2) ? RateLimitInfo.at(r2, null, "x", "2") : null;
        assertThatThrownBy(new LimitScript(source, journal, clock, gate)::run).isInstanceOf(RunSuspended.class);
        clock.set(r1);
        assertThatThrownBy(new LimitScript(source, journal, clock, gate)::run).isInstanceOf(RunSuspended.class);
        clock.set(r2);
        LimitScript done = new LimitScript(source, journal, clock, gate).run();
        assertThat(done.executor.spend().total().tokens()).isEqualTo(450);
        assertThat(done.executor.spend().total().calls()).isEqualTo(5); // 3 answers + 2 refused (unbilled) calls
    }

    @Test
    void n1_aPausedRunHoldsNoThread() {
        // Only threads that appeared during this run count: the JVM is shared with other tests, whose own workers may still be inside the executor.
        java.util.Set<Thread> before = new java.util.HashSet<>(Thread.getAllStackTraces().keySet());
        LimitScript run = new LimitScript(THREE_STEPS, durable(), clock,
                LimitScript.limitedUntil(clock, "Drafter", Instant.parse("2026-09-27T11:00:00Z")));
        assertThatThrownBy(run::run).isInstanceOf(RunSuspended.class);
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            if (thread == Thread.currentThread() || before.contains(thread)) return;
            for (StackTraceElement frame : stack) {
                assertThat(frame.getClassName()).as("thread %s", thread.getName())
                        .doesNotStartWith("io.github.llm4j.loom.execution.HarnessExecutor");
            }
        });
    }
}
