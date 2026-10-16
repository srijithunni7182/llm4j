package io.github.llm4j.spring;

import static io.github.llm4j.spring.ClientsTest.rootConfigurationProblem;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.audit.AuditLogger;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.budget.Budget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Every setting is read, and every mistake in one is refused at start-up with the property named. V2, V3, V5, V6, V7, V9. */
class SettingsTest {

    @TempDir
    Path dir;

    private ApplicationContextRunner base() {
        return Fixtures.runner().withBean(LLMClientFactory.class, LoomFixtures.Models::new);
    }

    private static final String[] OLLAMA = {"llm4j.clients.default.provider=ollama", "llm4j.clients.default.model=m"};

    private static String[] with(String[] first, String... more) {
        List<String> all = new ArrayList<>(List.of(first));
        all.addAll(List.of(more));
        return all.toArray(new String[0]);
    }

    /** Start-up fails, and what it says contains {@code fragment}. */
    private void assertRefuses(String fragment, String... props) {
        base().withPropertyValues(props).run(ctx -> {
            assertThat(ctx).as(fragment).hasFailed();
            var problem = rootConfigurationProblem(ctx.getStartupFailure());
            assertThat(problem.getMessage() + " " + problem.action()).contains(fragment);
        });
    }

    /** The context starts (the agent builder is made on demand), and asking for a builder fails saying {@code fragment}. */
    private void assertRefusesBuilding(String fragment, String... props) {
        base().withPropertyValues(props).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> ctx.getBean(ReActAgent.Builder.class)).satisfies(e -> {
                var problem = rootConfigurationProblem(e);
                assertThat(problem.getMessage() + " " + problem.action()).contains(fragment);
            });
        });
    }

    private void assertStarts(String... props) {
        base().withPropertyValues(props).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Tag("SB-V2.3")
    @Test
    void secretStoreSettingsAreChecked() throws Exception {
        assertRefuses("llm4j.secrets.chain", "llm4j.secrets.store=chained");
        assertRefuses("cannot contain chained", "llm4j.secrets.store=chained", "llm4j.secrets.chain=chained");
        assertRefuses("not known", "llm4j.secrets.store=vault");
        assertRefuses("llm4j.secrets.file", "llm4j.secrets.store=encrypted-file");
        assertStarts("llm4j.secrets.store=ENV");
    }

    @Tag("SB-V3.1")
    @Test
    void aClientNeedsItsProviderAndAnyOptionalSettingIsRead() {
        assertRefuses("llm4j.clients.default.provider", "llm4j.clients.default.model=m");
        assertStarts(with(OLLAMA, "llm4j.clients.default.timeout=30s", "llm4j.clients.default.connect-timeout=5s", "llm4j.clients.default.base-url=http://localhost:11434/api"));
        assertStarts(with(OLLAMA, "llm4j.clients.default.base-url=http://localhost:11434"));
        assertStarts("llm4j.clients.default.provider=anthropic", "llm4j.clients.default.api-key=k", "llm4j.clients.default.effort=high", "llm4j.clients.default.model=m");
        assertStarts("llm4j.provider=ollama", "llm4j.model=m", "llm4j.timeout=10s", "llm4j.connect-timeout=2s", "llm4j.base-url=http://localhost:1");
    }

    @Tag("SB-V3.4")
    @Test
    void everyRetrySettingIsRead() {
        MutableClock clock = new MutableClock("2026-10-15T06:59:00Z");
        base().withBean(Clock.class, () -> clock).withBean(io.github.llm4j.ratelimit.Sleeper.class, () -> d -> { })
                .withPropertyValues(with(OLLAMA, "llm4j.clients.default.retry.max-retries=2", "llm4j.clients.default.retry.initial-backoff=10ms", "llm4j.clients.default.retry.max-backoff=1s",
                        "llm4j.clients.default.retry.retryable-status-codes=429,503", "llm4j.clients.default.retry.inline-wait-threshold=2s", "llm4j.clients.default.retry.fallback-delay=3s",
                        "llm4j.clients.default.retry.max-fallback-delay=1m", "llm4j.clients.default.retry.daily-reset-zone=UTC"))
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Tag("SB-V3.5")
    @Test
    void routingNeedsAKnownStrategyAndItsClients() {
        assertRefuses("not known", with(OLLAMA, "llm4j.routing.strategy=round-robin"));
        assertRefuses("llm4j.routing.clients", with(OLLAMA, "llm4j.routing.strategy=fallback"));
        assertRefuses("tier", with(OLLAMA, "llm4j.clients.default.tier=turbo", "llm4j.routing.strategy=fallback", "llm4j.routing.clients=default"));
    }

    @Tag("SB-V4.1")
    @Test
    void budgetsPricesPrivacyAndAuditSettingsAreRead() throws Exception {
        assertRefuses("llm4j.budget.cost", with(OLLAMA, "llm4j.budget.cost=5.00"));
        assertRefuses("window", with(OLLAMA, "llm4j.budget.tokens=100", "llm4j.budget.per-call-max-tokens=10", "llm4j.budget.window=week"));
        assertRefuses("llm4j.pricing.file", with(OLLAMA, "llm4j.budget.tokens=100", "llm4j.pricing.file=file:" + dir.resolve("none.properties")));
        Files.writeString(dir.resolve("prices.properties"), "m = 3 / 15\n");
        base().withPropertyValues(with(OLLAMA, "llm4j.pricing.file=file:" + dir.resolve("prices.properties"), "llm4j.budget.cost=$5.00", "llm4j.budget.per-call-max-tokens=100",
                "llm4j.privacy.mask-pii=true", "llm4j.privacy.types=EMAIL,PHONE", "llm4j.audit.file=" + dir.resolve("a.jsonl"), "llm4j.audit.max-file-size=1MB", "llm4j.audit.max-files=3"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(Budget.class)).isNotNull();
                    assertThat(ctx.getBean(AuditLogger.class)).isNotNull();
                    assertThat(ctx.getBean(Llm4jProperties.class).budget().toString()).contains("5.00");
                    assertThat(ctx.getBean(Llm4jProperties.class).clients().toString()).doesNotContain("secret-value");
                });
        assertStarts(with(OLLAMA, "llm4j.audit.file=" + dir.resolve("b.jsonl"), "llm4j.audit.max-files=2"));
        assertStarts(with(OLLAMA, "llm4j.audit.file=" + dir.resolve("c.jsonl"), "llm4j.audit.max-file-size=2MB"));
    }

    @Tag("SB-V5.1")
    @Test
    void everyAgentSettingIsRead() throws Exception {
        Files.writeString(dir.resolve("windows.txt"), "gemini-2.5-flash = 8000\n");
        Files.createDirectories(dir.resolve("skills"));
        Files.copy(Path.of("src/test/resources/skills/refunds.md"), dir.resolve("skills/refunds.md"));
        base().withPropertyValues(with(OLLAMA, "llm4j.agent.system-prompt=be brief", "llm4j.agent.instructions=extra", "llm4j.agent.temperature=0.3", "llm4j.agent.tool-calling=auto",
                "llm4j.agent.streaming=false", "llm4j.agent.reasoning=false", "llm4j.agent.validate-tool-arguments=true", "llm4j.agent.max-tokens-per-call=500",
                "llm4j.agent.on-budget-exhausted=fail", "llm4j.agent.max-iterations=3", "llm4j.agent.context.windows-file=" + dir.resolve("windows.txt"),
                "llm4j.skills.locations=file:" + dir.resolve("skills") + ",classpath:skills/refunds.md", "llm4j.agent.skills=Refunds")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ReActAgent.Builder.class)).isNotNull();
        });
        base().withPropertyValues(with(OLLAMA, "llm4j.skills.locations=file:" + dir.resolve("skills/refunds.md"))).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Tag("SB-V5.1")
    @Test
    void agentMistakesAreRefused() throws Exception {
        assertRefusesBuilding("both set", with(OLLAMA, "llm4j.agent.system-prompt=a", "llm4j.agent.system-prompt-id=b"));
        assertRefusesBuilding("no prompt registry", with(OLLAMA, "llm4j.agent.system-prompt-id=triage"));
        assertRefusesBuilding("llm4j.agent.tool-calling", with(OLLAMA, "llm4j.agent.tool-calling=sometimes"));
        assertRefusesBuilding("llm4j.agent.skills", with(OLLAMA, "llm4j.agent.skills=Refunds"));
        assertRefusesBuilding("not loaded", with(OLLAMA, "llm4j.skills.locations=classpath:skills/refunds.md", "llm4j.agent.skills=nothing"));
        assertRefusesBuilding("windows-file", with(OLLAMA, "llm4j.agent.context.windows-file=" + dir.resolve("none.txt")));
        assertRefuses("allow-remote", "llm4j.skills.locations=https://example.com/skill.md");
        assertRefuses("llm4j.skills.locations", "llm4j.skills.locations=file:" + dir.resolve("none.md"));
        assertRefuses("llm4j.memory.dir", "llm4j.memory.conversations=file");
        assertRefuses("not known", "llm4j.memory.conversations=redis");
        Files.writeString(dir.resolve("plain.txt"), "x");
        assertRefuses("neither a folder", "llm4j.prompts.location=file:" + dir.resolve("plain.txt"));
        assertStarts("llm4j.memory.conversations=file", "llm4j.memory.dir=" + dir.resolve("conv"));
    }

    @Tag("SB-V5.1")
    @Test
    void aYamlPromptsFileIsAccepted() throws Exception {
        Files.writeString(dir.resolve("prompts.yaml"), "prompts:\n  - id: hello\n    content: Hello\n");
        assertStarts("llm4j.prompts.location=file:" + dir.resolve("prompts.yaml"));
    }

    @Tag("SB-V6.5")
    @Test
    void aBuiltInToolThatNeedsAKeyAsksForIt() {
        assertRefuses("SERPAPI_API_KEY", "llm4j.tools.builtin=serpapi");
        assertStarts("llm4j.tools.builtin=serpapi", "SERPAPI_API_KEY=k");
    }

    @Tag("SB-V7.1")
    @Test
    void embeddingAndStoreMistakesAreRefused() {
        assertRefuses("llm4j.rag.api-key-secret", "llm4j.rag.embedding=gemini");
        assertRefuses("not known", "llm4j.rag.embedding=word2vec");
        assertRefuses("llm4j.rag.store", "llm4j.rag.store=chroma");
        assertRefuses("llm4j.rag.file", "llm4j.rag.store=file");
        assertRefuses("llm4j.rag.model", "llm4j.rag.embedding=onnx");
        assertRefuses("llm4j.rag.tokenizer", "llm4j.rag.embedding=onnx", "llm4j.rag.model=m.onnx");
        assertRefuses("could not load", "llm4j.rag.embedding=djl", "llm4j.rag.model=file:///nope");
        assertStarts("llm4j.rag.embedding=gemini", "llm4j.rag.api-key=k", "llm4j.rag.model=text-embedding-004");
    }

    @Tag("SB-V9.4")
    @Test
    void loomMistakesAreRefused() throws Exception {
        String script = "llm4j.loom.script=classpath:scripts/three-steps.loom";
        assertRefuses("does not exist", "llm4j.loom.script=classpath:scripts/none.loom");
        assertRefuses("llm4j.loom.loot", script, "llm4j.loom.loot=classpath:scripts/none.loot");
        assertRefuses("llm4j.loom.dir", script, "llm4j.loom.persistence=file");
        assertRefuses("not known", script, "llm4j.loom.persistence=redis");
        assertRefuses("not known", script, "llm4j.loom.executor=fibers");
        base().withBean(javax.sql.DataSource.class, LoomFixtures::h2).withPropertyValues(script, "llm4j.loom.persistence=jdbc", "llm4j.loom.initialize-schema=sometimes").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).getMessage()).contains("llm4j.loom.initialize-schema");
        });
        Files.writeString(dir.resolve("bad-windows.txt"), "this is not a window\n");
        assertRefuses("windows-files", script, "llm4j.loom.context.windows-files=file:" + dir.resolve("bad-windows.txt"));
        Files.writeString(dir.resolve("windows.txt"), "test/model = 5000\n");
        String price = "llm4j.pricing.models[test/model].input-per-million=1";
        String out = "llm4j.pricing.models[test/model].output-per-million=2";
        assertStarts(script, price, out, "llm4j.loom.budget.tokens=100000", "llm4j.loom.budget.calls=50", "llm4j.loom.budget.cost=$2.50", "llm4j.loom.context.windows-files=file:" + dir.resolve("windows.txt"));
        assertStarts(script, price, out, "llm4j.loom.budget.cost=1.00");
    }

    @Tag("SB-V10.2")
    @Test
    void anExistingSchemaIsAcceptedWhenSchemaCreationIsOff() {
        javax.sql.DataSource ds = LoomFixtures.h2();
        io.github.llm4j.loom.host.LoomHost.createSchema(ds);
        base().withBean(javax.sql.DataSource.class, () -> ds).withPropertyValues("llm4j.loom.script=classpath:scripts/three-steps.loom", "llm4j.loom.persistence=jdbc", "llm4j.loom.initialize-schema=never")
                .run(ctx -> assertThat(ctx).hasNotFailed());
        base().withBean(javax.sql.DataSource.class, LoomFixtures::h2).withPropertyValues("llm4j.loom.script=classpath:scripts/three-steps.loom", "llm4j.loom.persistence=jdbc",
                "llm4j.loom.initialize-schema=always").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Tag("SB-V12.5")
    @Test
    void theRunPageSettingsAreRead() throws Exception {
        Files.writeString(dir.resolve("rules.yaml"), "rules: []\n");
        RecordingObserveProvider.reset();
        assertStarts("llm4j.observe.enabled=true", "llm4j.observe.port=0", "llm4j.observe.detail=full", "llm4j.observe.linger=1s", "llm4j.observe.rules-file=file:" + dir.resolve("rules.yaml"));
        assertThat(RecordingObserveProvider.OPENED).hasSize(1);
        assertRefuses("llm4j.observe", "llm4j.observe.enabled=true", "llm4j.observe.detail=everything");
    }
}
