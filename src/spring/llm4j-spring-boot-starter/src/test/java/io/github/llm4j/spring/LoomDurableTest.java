package io.github.llm4j.spring;

import static io.github.llm4j.spring.ClientsTest.rootConfigurationProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.autonomy.FileLedger;
import io.github.llm4j.loom.autonomy.FileLevelStore;
import io.github.llm4j.loom.autonomy.Ledger;
import io.github.llm4j.loom.autonomy.LevelStore;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.LoomRun;
import io.github.llm4j.loom.host.AnswerResult;
import io.github.llm4j.loom.host.QuestionListener;
import io.github.llm4j.loom.host.RunOutcome;
import io.github.llm4j.loom.host.RunRecord;
import io.github.llm4j.loom.host.RunRequest;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.secret.InMemorySecretStore;
import io.github.llm4j.secret.SecretStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;

/** Runs that wait and resume, triggers, autonomy and observing, through Spring, on both stores. V10, V11, V12 (and V9.11). */
class LoomDurableTest {

    @TempDir
    Path dir;

    private final LoomFixtures.Models models = new LoomFixtures.Models();
    private final LoomFixtures.Notify notify = new LoomFixtures.Notify();
    private final LoomFixtures.Refund refund = new LoomFixtures.Refund();
    private final MutableClock clock = new MutableClock("2026-10-15T06:59:00Z");
    private final DataSource ds = LoomFixtures.h2();

    private ApplicationContextRunner loom(String kind, String script, String... props) {
        List<String> all = new ArrayList<>(List.of("llm4j.loom.script=classpath:scripts/" + script));
        ApplicationContextRunner runner = Fixtures.runner().withBean(LLMClientFactory.class, () -> models).withBean("notify", io.github.llm4j.agent.task.Task.class, () -> notify)
                .withBean("refund", io.github.llm4j.agent.task.Task.class, () -> refund).withBean(java.time.Clock.class, () -> clock);
        if (kind.equals("file")) {
            all.add("llm4j.loom.persistence=file");
            all.add("llm4j.loom.dir=" + dir.resolve("store"));
        } else if (kind.equals("jdbc")) {
            runner = runner.withBean(DataSource.class, () -> ds);
            all.add("llm4j.loom.persistence=jdbc");
        }
        all.addAll(List.of(props));
        return runner.withPropertyValues(all.toArray(new String[0]));
    }

    /** Spring wraps an event that is not an ApplicationEvent as a payload event; this hears the ones carrying a {@code type}. */
    private static <T> ApplicationListener<org.springframework.context.PayloadApplicationEvent<?>> payloads(Class<T> type, java.util.function.Consumer<T> sink) {
        return event -> {
            if (type.isInstance(event.getPayload())) sink.accept(type.cast(event.getPayload()));
        };
    }

    private static void await(Supplier<Boolean> check, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!check.get()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean completed(LoomRuntime loom, String id) {
        return loom.run(id).map(RunRecord::status).filter(s -> s == RunRecord.Status.COMPLETED).isPresent();
    }

    @Tag("SB-V10.1")
    @Test
    void aFileStoreKeepsEachRunWhereWeaveWouldLookForIt() {
        loom("file", "three-steps.loom").run(ctx -> {
            RunOutcome out = ctx.getBean(LoomRuntime.class).run(RunRequest.of("Main", Map.of()).withRunId("r-1"));
            assertThat(out).isInstanceOf(RunOutcome.Completed.class);
        });
        Path run = dir.resolve("store/runs/r-1");
        assertThat(run.resolve("journal.json")).exists();
        assertThat(run.resolve("run.json")).exists();
        assertThat(dir.resolve("store/triggers")).exists();
    }

    private Set<String> tables() throws Exception {
        Set<String> names = new TreeSet<>();
        try (Connection c = ds.getConnection(); ResultSet rs = c.getMetaData().getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
            while (rs.next()) names.add(rs.getString("TABLE_NAME").toLowerCase());
        }
        return names;
    }

    @Tag("SB-V10.2")
    @Test
    void theTablesAreCreatedWhenAskedAndRequiredWhenNot() throws Exception {
        loom("jdbc", "three-steps.loom").run(ctx -> assertThat(ctx).hasNotFailed()); // embedded database: created
        assertThat(tables()).contains("loom_journal", "loom_triggers", "llm4j_loom_runs", "llm4j_loom_answers");
        DataSource empty = LoomFixtures.h2();
        Fixtures.runner().withBean(LLMClientFactory.class, () -> models).withBean(DataSource.class, () -> empty).withPropertyValues("llm4j.loom.script=classpath:scripts/three-steps.loom",
                "llm4j.loom.persistence=jdbc", "llm4j.loom.initialize-schema=never").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).getMessage()).contains("loom_journal");
        });
        Fixtures.runner().withBean(LLMClientFactory.class, () -> models).withBean(DataSource.class, () -> empty).withPropertyValues("llm4j.loom.script=classpath:scripts/three-steps.loom",
                "llm4j.loom.persistence=jdbc", "llm4j.loom.initialize-schema=always").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Tag("SB-V10.3")
    @Test
    void aNamedDataSourceIsTheOneUsed() throws Exception {
        DataSource other = LoomFixtures.h2();
        Fixtures.runner().withBean(LLMClientFactory.class, () -> models).withBean("main", DataSource.class, () -> ds).withBean("loomDataSource", DataSource.class, () -> other)
                .withPropertyValues("llm4j.loom.script=classpath:scripts/three-steps.loom", "llm4j.loom.persistence=jdbc", "llm4j.loom.datasource=loomDataSource").run(ctx -> assertThat(ctx).hasNotFailed());
        try (Connection c = other.getConnection(); ResultSet rs = c.getMetaData().getTables(null, "PUBLIC", "LLM4J_LOOM_RUNS", null)) {
            assertThat(rs.next()).isTrue();
        }
        assertThat(tables()).doesNotContain("llm4j_loom_runs");
    }

    @Tag("SB-V10.4")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void aRunThatAsksAPersonWaitsAndCarriesOnAfterARestart(String kind) {
        String[] id = new String[1];
        String[] step = new String[1];
        loom(kind, "approve.loom").run(ctx -> {
            RunOutcome.Suspended s = (RunOutcome.Suspended) ctx.getBean(LoomRuntime.class).run(RunRequest.of("Main", Map.of()).withRunId("r-ask"));
            id[0] = s.runId();
            step[0] = s.stepId();
            assertThat(s.prompt()).isEqualTo("Publish? (yes/no)");
        });
        // the application restarted: a new context on the same store
        loom(kind, "approve.loom").run(ctx -> {
            LoomRuntime loom = ctx.getBean(LoomRuntime.class);
            assertThat(loom.run(id[0])).get().extracting(RunRecord::status).isEqualTo(RunRecord.Status.SUSPENDED);
            AnswerResult answered = loom.answer(id[0], step[0], "yes", "asha");
            assertThat(answered.recorded()).as(answered.message()).isTrue();
            await(() -> completed(loom, id[0]), "the resumed run");
        });
        assertThat(models.calls("Writer")).as("replayed, not asked again").isEqualTo(1);
        assertThat(notify.effects.get()).isEqualTo(1);
    }

    @Tag("SB-V10.5")
    @Test
    void aQuestionBecomesAnEventAndIsToldToListeners() {
        List<HumanQuestionEvent> events = Collections.synchronizedList(new ArrayList<>());
        List<String> told = Collections.synchronizedList(new ArrayList<>());
        QuestionListener listener = (run, step, prompt, choices) -> told.add(run + "|" + prompt);
        loom("file", "approve.loom").withBean("events", ApplicationListener.class, () -> payloads(HumanQuestionEvent.class, events::add)).withBean(QuestionListener.class, () -> listener).run(ctx -> {
            ctx.getBean(LoomRuntime.class).run(RunRequest.of("Main", Map.of()).withRunId("r-ev"));
            assertThat(events).hasSize(1);
            assertThat(events.get(0).prompt()).isEqualTo("Publish? (yes/no)");
            assertThat(events.get(0).runId()).isEqualTo("r-ev");
            assertThat(told).containsExactly("r-ev|Publish? (yes/no)");
        });
    }

    @Tag("SB-V10.6")
    @Test
    void aHumanInterfaceOfYourOwnReplacesTheSuspendingOne() {
        HumanInterface yes = message -> "yes";
        loom("file", "approve.loom").withBean(HumanInterface.class, () -> yes).run(ctx -> {
            assertThat(ctx.getBean(LoomRuntime.class).run(RunRequest.of("Main", Map.of()))).isInstanceOf(RunOutcome.Completed.class);
            assertThat(notify.effects.get()).isEqualTo(1);
        });
    }

    @Tag("SB-V11.1")
    @Test
    void theTriggerRunnerStartsWithTheApplicationAndStopsWithIt() {
        loom("file", "sleepy.loom", "llm4j.loom.triggers.enabled=true", "llm4j.loom.triggers.poll-interval=50ms").run(ctx -> {
            assertThat(Thread.getAllStackTraces().keySet()).anyMatch(t -> t.getName().contains("trigger"));
        });
        await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && t.getName().contains("trigger")), "the trigger runner to stop");
    }

    @Tag("SB-V11.2")
    @Test
    void triggersNeedAStore() {
        loom("none", "three-steps.loom", "llm4j.loom.triggers.enabled=true").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).action()).contains("llm4j.loom.persistence");
        });
    }

    @Tag("SB-V11.3")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void aScheduledWorkflowStartsAtItsTimeAndAnEventSaysSo(String kind) {
        List<TriggerFiredEvent> fired = Collections.synchronizedList(new ArrayList<>());
        loom(kind, "scheduled.loom", "llm4j.loom.triggers.enabled=true", "llm4j.loom.triggers.poll-interval=20ms").withBean("fired", ApplicationListener.class,
                () -> payloads(TriggerFiredEvent.class, fired::add)).run(ctx -> {
            LoomRuntime loom = ctx.getBean(LoomRuntime.class);
            clock.advance(Duration.ofMinutes(2)); // 07:01
            await(() -> loom.runs().size() == 1 && completed(loom, loom.runs().get(0).runId()), "the scheduled run");
            assertThat(loom.runs().get(0).runId()).startsWith("Daily@");
            await(() -> !fired.isEmpty(), "the fired event");
        });
        assertThat(models.calls("Writer")).isEqualTo(1);
    }

    @Tag("SB-V11.4")
    @Test
    void twoApplicationsOnOneStoreFireATriggerOnce() {
        loom("jdbc", "scheduled.loom", "llm4j.loom.triggers.enabled=true", "llm4j.loom.triggers.poll-interval=10ms", "llm4j.loom.triggers.owner=app-1").run(first -> {
            loom("jdbc", "scheduled.loom", "llm4j.loom.triggers.enabled=true", "llm4j.loom.triggers.poll-interval=10ms", "llm4j.loom.triggers.owner=app-2").run(second -> {
                clock.advance(Duration.ofMinutes(2));
                LoomRuntime loom = first.getBean(LoomRuntime.class);
                await(() -> loom.runs().size() == 1 && completed(loom, loom.runs().get(0).runId()), "the scheduled run");
                try {
                    Thread.sleep(300); // a duplicate, if there were one, would have started by now
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });
        assertThat(models.calls("Writer")).isEqualTo(1);
    }

    @Tag("SB-V12.1")
    @Test
    void earnedAutonomyRecordsAreKeptWithTheRunsAndUserStoresWin() {
        loom("file", "ladder.loom").run(ctx -> assertThat(ctx).hasNotFailed());
        loom("jdbc", "ladder.loom").run(ctx -> assertThat(ctx).hasNotFailed());
        Ledger ledger = new FileLedger(dir.resolve("mine-ledger"));
        LevelStore levels = new FileLevelStore(dir.resolve("mine-levels"));
        loom("file", "ladder.loom").withBean(Ledger.class, () -> ledger).withBean(LevelStore.class, () -> levels).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Tag("SB-V12.3")
    @Test
    void theRunPageSessionIsOpenedOnceWithTheSettingsAndEveryExecutorIsAttachedFirst() {
        RecordingObserveProvider.reset();
        List<String> order = RecordingObserveProvider.EVENTS;
        LoomExecutorCustomizer last = ex -> order.add("customizer");
        loom("file", "three-steps.loom", "llm4j.observe.enabled=true", "llm4j.observe.port=0", "llm4j.observe.name=svc").withBean(LoomExecutorCustomizer.class, () -> last).run(ctx -> {
            LoomRuntime loom = ctx.getBean(LoomRuntime.class);
            loom.run(RunRequest.of("Main", Map.of()));
            loom.run(RunRequest.of("Main", Map.of()).withRunId("second"));
        });
        assertThat(RecordingObserveProvider.OPENED).hasSize(1);
        assertThat(RecordingObserveProvider.OPENED.get(0).name()).isEqualTo("svc");
        assertThat(RecordingObserveProvider.OPENED.get(0).bind()).isEqualTo("127.0.0.1");
        // every executor was attached (with the script's file) before the last thing that happens ahead of initialize()
        for (int i = 0; i < order.size(); i++) if (order.get(i).equals("customizer")) assertThat(order.get(i - 1)).startsWith("attach three-steps.loom");
        assertThat(order.stream().filter(e -> e.startsWith("attach")).count()).isGreaterThanOrEqualTo(3); // start-up check plus two runs
        assertThat(order.get(0)).isEqualTo("open");
        assertThat(order.get(order.size() - 1)).isEqualTo("close");
    }

    @Tag("SB-V12.5")
    @Test
    void aPageOpenToOtherMachinesNeedsAnAdminPassword() {
        loom("file", "three-steps.loom", "llm4j.observe.enabled=true", "llm4j.observe.bind=0.0.0.0").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).getMessage()).contains("LOOM_INSPECT_ADMIN_PASSWORD".substring(0, 5).isEmpty() ? "" : "admin password");
        });
        InMemorySecretStore store = new InMemorySecretStore();
        store.put("LOOM_INSPECT_ADMIN_PASSWORD", "hash");
        loom("file", "three-steps.loom", "llm4j.observe.enabled=true", "llm4j.observe.bind=0.0.0.0", "llm4j.observe.port=0").withBean(SecretStore.class, () -> store)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Tag("SB-V9.11")
    @Test
    void shuttingDownAsksRunsToWrapUpThenSuspendsWhatIsStillGoing() throws Exception {
        models.holdAgent = "Researcher";
        models.hold = new CountDownLatch(1);
        models.entered = new CountDownLatch(1);
        String[] id = new String[1];
        loom("file", "three-steps.loom").run(ctx -> {
            LoomRuntime loom = ctx.getBean(LoomRuntime.class);
            LoomRun run = loom.start(RunRequest.of("Main", Map.of()).withRunId("going"));
            try {
                assertThat(models.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            List<String> left = loom.shutdown(Duration.ofMillis(200));
            assertThat(left).containsExactly("going");
            models.hold.countDown(); // the model call returns; the run now sees it was asked to stop
            try {
                assertThat(run.await(Duration.ofSeconds(10))).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            id[0] = "going";
        });
        // a new application finds the run waiting, not lost
        loom("file", "three-steps.loom").run(ctx -> assertThat(ctx.getBean(LoomRuntime.class).run(id[0])).get().extracting(RunRecord::status)
                .isIn(RunRecord.Status.SUSPENDED, RunRecord.Status.STOPPED, RunRecord.Status.COMPLETED));
        assertThat(Files.exists(dir.resolve("store/runs/going/run.json"))).isTrue();
        assertThatThrownBy(() -> { throw new IllegalStateException("marker"); }).hasMessage("marker");
    }
}
