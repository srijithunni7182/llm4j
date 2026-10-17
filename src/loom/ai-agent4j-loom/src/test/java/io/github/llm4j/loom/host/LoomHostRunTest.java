package io.github.llm4j.loom.host;

import static io.github.llm4j.loom.host.HostEnv.AGENTS;
import static io.github.llm4j.loom.host.HostEnv.APPROVE;
import static io.github.llm4j.loom.host.HostEnv.SLEEPY;
import static io.github.llm4j.loom.host.HostEnv.THREE_STEPS;
import static io.github.llm4j.loom.host.HostEnv.suspended;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.LoomRun;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Running, suspending, resuming and answering, on both stores. Verification plan V8.1–V8.17. */
class LoomHostRunTest {

    @TempDir
    Path dir;

    @Tag("SB-V8.1")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void aWorkflowRunsToTheEnd(String kind) {
        HostEnv env = new HostEnv(kind, dir).open(THREE_STEPS);
        RunOutcome out = env.host.run(RunRequest.of("Main", Map.of("topic", "x")));
        assertThat(out).isInstanceOfSatisfying(RunOutcome.Completed.class, c -> {
            assertThat(c.variables()).containsEntry("research", "Researcher#1").containsEntry("draft", "Drafter#1").containsEntry("review", "Reviewer#1");
        });
        assertThat(env.models.total()).isEqualTo(3);
        assertThat(env.host.run(out.runId())).get().extracting(RunRecord::status).isEqualTo(RunRecord.Status.COMPLETED);
    }

    @Tag("SB-V8.2")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void aQuestionSuspendsTheRunWithoutAnException(String kind) {
        HostEnv env = new HostEnv(kind, dir).open(APPROVE);
        RunOutcome.Suspended s = suspended(env.host.run(RunRequest.of("Main", Map.of())));
        assertThat(s.reason()).isEqualTo(RunSuspended.Reason.HUMAN);
        assertThat(s.prompt()).isEqualTo("Publish? (yes/no)");
        assertThat(s.stepId()).isNotBlank();
        RunRecord rec = env.host.run(s.runId()).orElseThrow();
        assertThat(rec.status()).isEqualTo(RunRecord.Status.SUSPENDED);
        assertThat(rec.prompt()).isEqualTo("Publish? (yes/no)");
    }

    @Tag("SB-V8.3")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void aRunStoppedByAnOperatorIsStoppedNotFailed(String kind) throws Exception {
        HostEnv env = new HostEnv(kind, dir).open(THREE_STEPS);
        env.models.holdAgent = "Drafter";
        env.models.holdAt = new CountDownLatch(1);
        env.models.entered = new CountDownLatch(1);
        LoomRun run = env.host.start(RunRequest.of("Main", Map.of()).withRunId("r-stop"));
        assertThat(env.models.entered.await(10, TimeUnit.SECONDS)).isTrue();
        run.cancel("*", io.github.llm4j.loom.execution.Outcome.byTheScript(), "enough");
        env.models.holdAt.countDown();
        assertThat(run.await(Duration.ofSeconds(10))).isTrue();
        HostEnv.await(() -> env.host.run("r-stop").map(RunRecord::status).filter(st -> st != RunRecord.Status.RUNNING).isPresent(), "the run to settle");
        assertThat(env.host.run("r-stop").orElseThrow().status()).isIn(RunRecord.Status.STOPPED, RunRecord.Status.COMPLETED);
    }

    @Tag("SB-V8.4")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void aFailureIsThrownWithTheRunId(String kind) {
        HostEnv env = new HostEnv(kind, dir).open(THREE_STEPS);
        env.models.failAgent = "Drafter";
        assertThatThrownBy(() -> env.host.run(RunRequest.of("Main", Map.of()).withRunId("r-fail")))
                .isInstanceOfSatisfying(LoomRunFailedException.class, e -> assertThat(e.runId()).isEqualTo("r-fail"));
        assertThat(env.host.run("r-fail").orElseThrow().status()).isEqualTo(RunRecord.Status.FAILED);
    }

    @Tag("SB-V8.5")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void theExecutorIsShutDownWhateverTheOutcome(String kind) throws Exception {
        String both = HostEnv.THREE_STEPS + "\nworkflow Ask() { human_prompt \"Go?\" -> ok }\n";
        HostEnv env = new HostEnv(kind, dir).open(both);
        int launched = 0;
        env.host.run(RunRequest.of("Main", Map.of()).withRunId("a"));                      // completed
        env.host.run(RunRequest.of("Ask", Map.of()).withRunId("b"));                       // suspended
        env.models.failAgent = "Drafter";
        assertThatThrownBy(() -> env.host.run(RunRequest.of("Main", Map.of()).withRunId("c"))).isInstanceOf(LoomRunFailedException.class); // failed
        env.models.failAgent = null;
        assertThat(env.host.shutdownCount()).isEqualTo(3);
        LoomRun ok = env.host.start(RunRequest.of("Main", Map.of()).withRunId("d"));      // completed, in the background
        assertThat(ok.await(Duration.ofSeconds(10))).isTrue();
        env.models.failAgent = "Drafter";
        LoomRun bad = env.host.start(RunRequest.of("Main", Map.of()).withRunId("e"));     // failed, in the background
        assertThat(bad.await(Duration.ofSeconds(10))).isTrue();
        env.models.failAgent = null;
        HostEnv.await(() -> env.host.shutdownCount() == 5, "both background executors to be shut down");
        LoomRun asks = env.host.start(RunRequest.of("Ask", Map.of()).withRunId("f"));     // suspended, in the background
        assertThat(asks.await(Duration.ofSeconds(10))).isTrue();
        HostEnv.await(() -> env.host.shutdownCount() == 6, "the suspended executor to be shut down");
        assertThat(ok.status()).isEqualTo(LoomRun.Status.COMPLETED);
        assertThat(bad.status()).isEqualTo(LoomRun.Status.FAILED);
        assertThat(asks.status()).isEqualTo(LoomRun.Status.SUSPENDED);
        // a run that was started and finished can be started again under the same id: the host released it
        assertThat(env.host.run(RunRequest.of("Main", Map.of()).withRunId("a"))).isInstanceOf(RunOutcome.class);
    }

    @Tag("SB-V8.6")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void startReturnsBeforeTheFirstModelCallAndUsesTheGivenExecutor(String kind) throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> thread = new java.util.concurrent.atomic.AtomicReference<>();
        HostEnv env = new HostEnv(kind, dir).open(THREE_STEPS, b -> b.runner(task -> {
            Thread t = new Thread(() -> { thread.set(Thread.currentThread().getName()); task.run(); }, "my-pool-thread");
            t.setDaemon(true);
            t.start();
        }));
        env.models.holdAgent = "Researcher";
        env.models.holdAt = new CountDownLatch(1);
        env.models.entered = new CountDownLatch(1);
        LoomRun run = env.host.start(RunRequest.of("Main", Map.of()));
        assertThat(env.models.entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(env.models.total()).isZero(); // start has returned and the model has not answered yet
        env.models.holdAt.countDown();
        assertThat(run.await(Duration.ofSeconds(10))).isTrue();
        assertThat(thread.get()).isEqualTo("my-pool-thread");
        assertThat(run.status()).isEqualTo(LoomRun.Status.COMPLETED);
    }

    @Tag("SB-V8.10")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void anAnswerResumesTheRunWithoutRepeatingAnything(String kind) throws Exception {
        HostEnv env = new HostEnv(kind, dir).open(APPROVE);
        RunOutcome.Suspended s = suspended(env.host.run(RunRequest.of("Main", Map.of()).withRunId("r-1")));
        assertThat(env.questions).hasSize(1).first().asString().startsWith("r-1|");
        AnswerResult r = env.host.answer("r-1", s.stepId(), "yes", "asha");
        assertThat(r.recorded()).as(r.message()).isTrue();
        HostEnv.await(() -> env.host.run("r-1").map(RunRecord::status).filter(st -> st == RunRecord.Status.COMPLETED).isPresent(), "the resumed run");
        assertThat(env.models.calls("Writer")).isEqualTo(1);   // replayed, not asked again
        assertThat(env.effects.get()).isEqualTo(1);            // the task ran once
        assertThat(env.questions).hasSize(1);                  // not asked again
    }

    @Tag("SB-V8.11")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void aRestartBetweenTheQuestionAndTheAnswerLosesNothing(String kind) {
        HostEnv env = new HostEnv(kind, dir).open(APPROVE);
        RunOutcome.Suspended s = suspended(env.host.run(RunRequest.of("Main", Map.of()).withRunId("r-2")));
        env.reopen(APPROVE);
        assertThat(env.host.answer("r-2", s.stepId(), "yes", "asha").recorded()).isTrue();
        HostEnv.await(() -> env.host.run("r-2").map(RunRecord::status).filter(st -> st == RunRecord.Status.COMPLETED).isPresent(), "the resumed run");
        assertThat(env.models.calls("Writer")).isEqualTo(1);
        assertThat(env.effects.get()).isEqualTo(1);
    }

    @Tag("SB-V8.12")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void anAnswerThatDoesNotFitIsRefusedWithAReason(String kind) {
        HostEnv env = new HostEnv(kind, dir).open(APPROVE);
        RunOutcome.Suspended s = suspended(env.host.run(RunRequest.of("Main", Map.of()).withRunId("r-3")));
        assertThat(env.host.answer("r-3", "no-such-step", "yes", "asha")).extracting(AnswerResult::message).asString().contains("not waiting at no-such-step");
        assertThat(env.host.answer("nope", s.stepId(), "yes", "asha").recorded()).isFalse();
        assertThat(env.host.answer("r-3", s.stepId(), "yes", "asha").recorded()).isTrue();
        HostEnv.await(() -> env.host.run("r-3").map(RunRecord::status).filter(st -> st == RunRecord.Status.COMPLETED).isPresent(), "the run");
        assertThat(env.host.answer("r-3", s.stepId(), "no", "bo")).satisfies(a -> {
            assertThat(a.recorded()).isFalse();
            assertThat(a.message()).containsAnyOf("already answered by asha", "not suspended");
        });
    }

    @Tag("SB-V8.13")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void choicesAreMatchedAndOtherAnswersRefused(String kind) {
        // a host whose runner never starts anything, so the resume that follows an answer is not what this test looks at
        HostEnv env = new HostEnv(kind, dir).open(APPROVE, b -> b.runner(task -> { }));
        Instant now = env.clock.instant();
        Persistence p = env.persistence();
        p.registry(null).save(new RunRecord("c-1", "Main", Map.of(), "app", RunRecord.Status.SUSPENDED, RunSuspended.Reason.HUMAN, null, "Main/s1", "Go?", null, now, now));
        p.answers().ask("c-1", "Main/s1", "Go?", List.of("yes", "no"), now);
        assertThat(env.host.answer("c-1", "Main/s1", "maybe", "asha")).satisfies(a -> {
            assertThat(a.recorded()).isFalse();
            assertThat(a.message()).contains("yes, no");
        });
        assertThat(p.answers().find("c-1", "Main/s1")).get().extracting(AnswerBook.Question::answered).isEqualTo(false);
        AnswerResult ok = env.host.answer("c-1", "Main/s1", "Ye", "asha"); // the start of exactly one choice
        assertThat(ok.recorded()).as(ok.message()).isTrue();
        assertThat(ok.message()).isEqualTo("Recorded: yes");
        assertThat(p.answers().find("c-1", "Main/s1")).get().satisfies(q -> {
            assertThat(q.answer()).isEqualTo("yes");
            assertThat(q.by()).isEqualTo("asha");
        });
        assertThat(env.host.answer("c-1", "Main/s1", "yes", "bo").recorded()).isFalse();
    }

    @Tag("SB-V8.14")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void aSleepingRunWakesWhenTheTriggerIsDue(String kind) {
        HostEnv env = new HostEnv(kind, dir).open(SLEEPY);
        RunOutcome.Suspended s = suspended(env.host.run(RunRequest.of("Main", Map.of()).withRunId("r-sleep")));
        assertThat(s.reason()).isEqualTo(RunSuspended.Reason.SLEEP);
        assertThat(s.resumeAt()).isEqualTo(env.clock.instant().plus(Duration.ofHours(1)));
        env.host.startTriggers(Duration.ofMillis(20), "test-host", Duration.ofMinutes(5), null);
        try {
            env.clock.advance(Duration.ofHours(1).plusSeconds(31)); // the wake-up trigger carries up to 30 s of jitter
            HostEnv.await(() -> env.host.run("r-sleep").map(RunRecord::status).filter(st -> st == RunRecord.Status.COMPLETED).isPresent(), "the run to wake");
        } finally {
            env.host.stopTriggers();
        }
        assertThat(env.models.calls("Researcher")).isEqualTo(1); // not called again
        assertThat(env.models.calls("Drafter")).isEqualTo(1);
    }

    @Tag("SB-V8.15")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void aRateLimitSuspendsUntilTheResetAndResumes(String kind) {
        Instant reset = Instant.parse("2026-09-27T10:30:00Z");
        HostEnv env = new HostEnv(kind, dir).open(THREE_STEPS + "\n", b -> { });
        env.models.gate = HostEnv.limitedUntil(env.clock, "Drafter", reset);
        RunOutcome.Suspended s = suspended(env.host.run(RunRequest.of("Main", Map.of()).withRunId("r-rate")));
        assertThat(s.reason()).isEqualTo(RunSuspended.Reason.RATE_LIMIT);
        assertThat(s.resumeAt()).isEqualTo(reset);
        env.clock.set(reset);
        assertThat(env.host.resume("r-rate", null)).isInstanceOf(RunOutcome.Completed.class);
        assertThat(env.models.calls("Researcher")).isEqualTo(1);
    }

    @Tag("SB-V8.16")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void aQuestionIsToldToListenersOnceWithItsPrompt(String kind) {
        HostEnv env = new HostEnv(kind, dir).open(APPROVE);
        RunOutcome.Suspended s = suspended(env.host.run(RunRequest.of("Main", Map.of()).withRunId("r-q")));
        assertThat(env.questions).containsExactly("r-q|" + s.stepId() + "|Publish? (yes/no)");
        env.host.resume("r-q", null); // asks again: the question is not put twice
        assertThat(env.questions).hasSize(1);
    }

    @Tag("SB-V8.17")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void aHumanInterfaceOfYourOwnIsUsedInstead(String kind) {
        HostEnv env = new HostEnv(kind, dir).open(APPROVE, b -> b.humanInterface(message -> "yes"));
        assertThat(env.host.run(RunRequest.of("Main", Map.of()))).isInstanceOf(RunOutcome.Completed.class);
        assertThat(env.questions).isEmpty();
        assertThat(env.effects.get()).isEqualTo(1);
    }

    @Tag("SB-V8.28")
    @ParameterizedTest @ValueSource(strings = {"none"})
    void withoutAStoreAScriptThatWaitsIsRefusedOrFailsItsRun(String kind) {
        HostEnv env = new HostEnv(kind, dir);
        assertThatThrownBy(() -> env.open(APPROVE)).isInstanceOf(IllegalStateException.class).hasMessageContaining("persistence").hasMessageContaining("human_prompt (line");
        env.open(APPROVE, b -> b.allowNonDurable(true));
        assertThatThrownBy(() -> env.host.run(RunRequest.of("Main", Map.of()))).isInstanceOf(LoomRunFailedException.class)
                .hasMessageContaining("no persistence");
        assertThatThrownBy(() -> env.host.resume("x", null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("no persistence");
        assertThat(env.host.answer("x", "s", "yes", "asha").recorded()).isFalse();
        String ladder = AGENTS + """
                ladder L { rung x { mode: blind } }
                agent A { model: "m" system: "s" output_schema: { choice: string, reasoning: string } }
                decision D { proposed by: A choices: yes, no ladder: L }
                workflow Main() { note "x" }
                """;
        assertThatThrownBy(() -> env.open(ladder, b -> b.allowNonDurable(true))).hasMessageContaining("decision D").hasMessageContaining("needs a store");
    }
}
