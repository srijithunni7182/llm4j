package io.github.llm4j.loom.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.audit.NoOpAuditLogger;
import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.loom.execution.LoomRun;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.observe.ObserveProvider;
import io.github.llm4j.secret.InMemorySecretStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Shutting down with runs in flight, every builder option, and the edges of answering. V8.5, V8.10, V8.12, V8.28. */
class LoomHostCoverageTest {

    @TempDir
    Path dir;

    private LoomRun blocked(HostEnv env, String id) throws Exception {
        env.models.holdAgent = "Researcher";
        env.models.holdAt = new CountDownLatch(1);
        env.models.entered = new CountDownLatch(1);
        LoomRun run = env.host.start(RunRequest.of("Main", Map.of()).withRunId(id));
        assertThat(env.models.entered.await(10, TimeUnit.SECONDS)).isTrue();
        return run;
    }

    @Tag("SB-V8.5")
    @Test
    void aDurableHostSuspendsARunThatOutlastsTheGraceAndRefusesNewOnes() throws Exception {
        HostEnv env = new HostEnv("file", dir).open(HostEnv.THREE_STEPS);
        LoomRun run = blocked(env, "going");
        List<String> left = env.host.shutdown(Duration.ofMillis(100));
        assertThat(left).containsExactly("going");
        env.models.holdAt.countDown();
        assertThat(run.await(Duration.ofSeconds(10))).isTrue();
        assertThatThrownBy(() -> env.host.run(RunRequest.of("Main", Map.of()))).isInstanceOf(IllegalStateException.class).hasMessageContaining("shutting down");
        assertThat(env.host.shutdown(Duration.ofMillis(10))).isEmpty();
    }

    @Tag("SB-V8.5")
    @Test
    void aHostWithNoStoreCancelsWhatOutlastsTheGraceAndCannotSuspend() throws Exception {
        HostEnv env = new HostEnv("none", dir).open(HostEnv.THREE_STEPS);
        LoomRun run = blocked(env, "going");
        assertThat(env.host.shutdown(Duration.ofMillis(100))).containsExactly("going");
        env.models.holdAt.countDown();
        assertThat(run.await(Duration.ofSeconds(10))).isTrue();
        assertThat(env.host.durable()).isFalse();
        HostEnv ask = new HostEnv("none", dir).open(HostEnv.APPROVE.replace("human_prompt", "human_prompt"), b -> b.allowNonDurable(true));
        assertThatThrownBy(() -> ask.host.run(RunRequest.of("Main", Map.of()))).isInstanceOf(LoomRunFailedException.class);
    }

    @Tag("SB-V8.1")
    @Test
    void everyBuilderOptionReachesTheExecutor() throws Exception {
        List<String> seen = new ArrayList<>();
        ObserveProvider.Session session = new ObserveProvider.Session() {
            @Override public void attach(io.github.llm4j.loom.observe.ObservableRun run) { seen.add("attach"); }
            @Override public void attach(io.github.llm4j.loom.observe.ObservableRun run, Path script) { seen.add("attach " + script.getFileName()); }
            @Override public String describe() { return "x"; }
            @Override public void close() { }
        };
        for (boolean withFile : new boolean[] {false, true}) {
            HostEnv env = new HostEnv("file", dir.resolve("f" + withFile)).open(HostEnv.THREE_STEPS, b -> {
                b.secretStore(new InMemorySecretStore()).promptRegistry(new io.github.llm4j.agent.prompt.MarkdownFolderPromptRegistry(dir)).priceTable(PriceTable.of(Map.of()))
                        .auditLogger(new NoOpAuditLogger()).budget(1_000_000L, 1000L, null).toolReach(Map.of()).lenient(true).observe(session)
                        .customizer(ex -> seen.add("customizer")).autonomy(new io.github.llm4j.loom.autonomy.FileLedger(dir.resolve("l")), new io.github.llm4j.loom.autonomy.FileLevelStore(dir.resolve("v")));
                if (withFile) b.scriptFile(dir.resolve("main.loom"));
            });
            assertThat(env.host.run(RunRequest.of("Main", Map.of()))).isInstanceOf(RunOutcome.Completed.class);
            assertThat(env.host.script()).isNotNull();
        }
        assertThat(seen).contains("attach", "attach main.loom", "customizer");
    }

    @Tag("SB-V8.12")
    @Test
    void anEmptyOrRepeatedOrUnaskedAnswerIsRefusedPlainly() {
        HostEnv env = new HostEnv("file", dir).open(HostEnv.APPROVE);
        RunOutcome.Suspended s = HostEnv.suspended(env.host.run(RunRequest.of("Main", Map.of()).withRunId("r")));
        assertThat(env.host.answer("r", s.stepId(), "  ", "asha").recorded()).isFalse();
        assertThat(env.host.answer("nope", s.stepId(), "yes", "asha").recorded()).isFalse();
        assertThat(env.host.answer("r", "other-step", "yes", "asha").recorded()).isFalse();
        assertThat(env.host.answer("r", s.stepId(), "yes", "asha").recorded()).isTrue();
        assertThat(env.host.answer("r", s.stepId(), "yes", "asha").recorded()).isFalse();
        assertThat(env.host.runs()).extracting(RunRecord::runId).contains("r");
        HostEnv.await(() -> env.host.run("r").filter(r -> r.status() == RunRecord.Status.COMPLETED).isPresent(), "the answered run to finish");
        env.host.close();
    }

    @Tag("SB-V8.28")
    @Test
    void persistenceKindsExposeTheirStores() {
        assertThat(Persistence.none().durable()).isFalse();
        assertThat(Persistence.none().ledger()).isNull();
        assertThat(Persistence.none().levels()).isNull();
        Persistence file = Persistence.file(dir);
        assertThat(file.durable()).isTrue();
        assertThat(file.ledger()).isNotNull();
        assertThat(file.levels()).isNotNull();
        javax.sql.DataSource ds = new HostEnv("jdbc", dir).ds;
        Persistence jdbc = Persistence.jdbc(ds);
        assertThat(jdbc.ledger()).isNotNull();
        assertThat(jdbc.levels()).isNotNull();
        assertThatThrownBy(() -> Persistence.jdbc(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LoomHost.createSchema(new org.h2.jdbcx.JdbcDataSource())).isInstanceOf(IllegalStateException.class);
    }
}
