package io.github.llm4j.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.memory.ConversationStore;
import io.github.llm4j.agent.memory.InMemoryConversationStore;
import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.store.InMemoryVectorStore;
import io.github.llm4j.agent.rag.store.VectorStore;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.tool.ToolRegistry;
import io.github.llm4j.audit.AuditLogger;
import io.github.llm4j.audit.NoOpAuditLogger;
import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.observe.ObserveProvider;
import io.github.llm4j.secret.InMemorySecretStore;
import io.github.llm4j.secret.SecretStore;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

/**
 * The rules the starter keeps so that it stays invisible: nothing unasked, yours wins, nothing
 * starts by itself. V0.
 */
class ZeroMagicTest {

    @TempDir Path dir;

    /**
     * One capability: the settings that switch it on (the first of them is the one that decides),
     * and the bean it creates.
     */
    private record Capability(String name, Class<?> bean, List<String> settings) {
        String[] all() {
            return settings.toArray(new String[0]);
        }
    }

    private List<Capability> capabilities() {
        String base = "llm4j.clients.default.provider=ollama";
        String model = "llm4j.clients.default.model=m";
        return List.of(
                new Capability("secrets", SecretStore.class, List.of("llm4j.secrets.store=env")),
                new Capability("clients", LLMClients.class, List.of(base, model)),
                new Capability("client", LLMClient.class, List.of(base, model)),
                new Capability(
                        "classifiers",
                        Classifiers.class,
                        List.of(
                                "llm4j.classifiers.d.provider=jev",
                                "llm4j.classifiers.d.api-key=literal-for-the-test")),
                new Capability(
                        "prices",
                        PriceTable.class,
                        List.of(
                                "llm4j.pricing.models.m.input-per-million=1",
                                "llm4j.pricing.models.m.output-per-million=1")),
                new Capability(
                        "budget", Budget.class, List.of("llm4j.budget.tokens=100", base, model)),
                new Capability(
                        "audit",
                        AuditLogger.class,
                        List.of("llm4j.audit.file=" + dir.resolve("audit.jsonl"))),
                new Capability("agent builder", ReActAgent.Builder.class, List.of(base, model)),
                new Capability(
                        "conversations",
                        ConversationStore.class,
                        List.of("llm4j.memory.conversations=memory")),
                new Capability(
                        "prompts",
                        PromptRegistry.class,
                        List.of("llm4j.prompts.location=classpath:prompts/")),
                new Capability(
                        "skills",
                        Llm4jAgentsAutoConfiguration.LoadedSkills.class,
                        List.of("llm4j.skills.locations=classpath:skills/refunds.md")),
                new Capability(
                        "vector store", VectorStore.class, List.of("llm4j.rag.store=memory")),
                new Capability(
                        "loom",
                        LoomRuntime.class,
                        List.of("llm4j.loom.script=classpath:scripts/three-steps.loom")),
                new Capability(
                        "tasks",
                        TaskRegistry.class,
                        List.of("llm4j.loom.script=classpath:scripts/three-steps.loom")),
                new Capability(
                        "run page",
                        ObserveProvider.Session.class,
                        List.of("llm4j.observe.enabled=true", "llm4j.observe.port=0")));
    }

    private ApplicationContextRunner runner() {
        return Fixtures.runner().withBean(LLMClientFactory.class, LoomFixtures.Models::new);
    }

    @Tag("SB-V0.1")
    @Test
    void anEmptyContextHasOnlyTheInertBeans() {
        Fixtures.runner()
                .run(
                        ctx -> {
                            List<String> ours = new ArrayList<>();
                            for (String name : ctx.getBeanDefinitionNames()) {
                                Class<?> type = ctx.getType(name);
                                if (type != null
                                        && type.getName().startsWith("io.github.llm4j.")
                                        && !type.getSimpleName().contains("AutoConfiguration"))
                                    ours.add(type.getSimpleName());
                            }
                            assertThat(ours)
                                    .containsExactlyInAnyOrder(
                                            "Llm4jProperties", "ToolCatalog", "ToolRegistry");
                            assertThat(ctx.getBean(ToolRegistry.class).getAllTools()).isEmpty();
                            assertThat(ctx.getBean(ToolCatalog.class).tools()).isEmpty();
                        });
    }

    @Tag("SB-V0.2")
    @Test
    void everyCapabilityAppearsWithItsSettingAndNotWithout() {
        for (Capability c : capabilities()) {
            runner().withPropertyValues(c.all())
                    .run(
                            ctx ->
                                    assertThat(ctx)
                                            .as(c.name() + " with its settings")
                                            .hasNotFailed()
                                            .hasSingleBean(c.bean()));
            runner().run(
                            ctx ->
                                    assertThat(ctx)
                                            .as(c.name() + " with nothing set")
                                            .hasNotFailed()
                                            .doesNotHaveBean(c.bean()));
        }
    }

    @Tag("SB-V0.2")
    @Test
    void theListOfCapabilitiesCoversEveryBeanTheStarterDefines() {
        Set<Class<?>> covered = new LinkedHashSet<>();
        capabilities().forEach(c -> covered.add(c.bean()));
        Set<Class<?>> inert =
                Set.of(
                        ToolCatalog.class,
                        ToolRegistry.class,
                        Llm4jLoomLifecycle.class,
                        org.springframework.beans.factory.config.BeanFactoryPostProcessor.class);
        List<String> missing = new ArrayList<>();
        for (Class<?> config :
                List.of(
                        Llm4jSecretsAutoConfiguration.class,
                        Llm4jClientsAutoConfiguration.class,
                        Llm4jToolsAutoConfiguration.class,
                        Llm4jAgentsAutoConfiguration.class,
                        Llm4jRagAutoConfiguration.class,
                        Llm4jLoomAutoConfiguration.class,
                        Llm4jObserveAutoConfiguration.class,
                        Llm4jGuardAutoConfiguration.class)) {
            for (Method m : config.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Bean.class)
                        && !covered.contains(m.getReturnType())
                        && !inert.contains(m.getReturnType())) {
                    // an embedding provider is the one capability that needs a provider's key to
                    // show here: it is covered with the vector store
                    if (m.getReturnType() != EmbeddingProvider.class)
                        missing.add(
                                config.getSimpleName()
                                        + "."
                                        + m.getName()
                                        + " returns "
                                        + m.getReturnType().getSimpleName());
                }
            }
        }
        assertThat(missing).as("a bean without a zero-magic case").isEmpty();
    }

    @Tag("SB-V0.3")
    @SuppressWarnings("unchecked")
    @Test
    void aBeanOfYourOwnAlwaysTakesThePlace() throws Exception {
        Map<Class<?>, Object> mine =
                Map.ofEntries(
                        Map.entry(SecretStore.class, new InMemorySecretStore()),
                        Map.entry(
                                LLMClient.class,
                                (LLMClient)
                                        new io.github.llm4j.DefaultLLMClient(
                                                new io.github.llm4j.provider.ollama.OllamaProvider(
                                                        io.github.llm4j.config.LLMConfig.builder()
                                                                .defaultModel("m")
                                                                .build()))),
                        Map.entry(PriceTable.class, PriceTable.of(Map.of())),
                        Map.entry(Budget.class, Budget.builder().tokens(1).build()),
                        Map.entry(AuditLogger.class, new NoOpAuditLogger()),
                        Map.entry(ConversationStore.class, new InMemoryConversationStore()),
                        Map.entry(VectorStore.class, new InMemoryVectorStore()),
                        Map.entry(ReActAgent.Builder.class, ReActAgent.builder()),
                        Map.entry(TaskRegistry.class, new TaskRegistry()),
                        Map.entry(ToolRegistry.class, new ToolRegistry()),
                        Map.entry(ToolCatalog.class, new ToolCatalog()));
        for (Capability c : capabilities()) {
            Object theirs = mine.get(c.bean());
            if (theirs == null)
                continue; // prompts, skills, the run page and Loom are covered below, where
            // building one needs more than a constructor
            Class<Object> type = (Class<Object>) c.bean();
            runner().withBean(c.bean().getName(), type, () -> theirs)
                    .withPropertyValues(c.all())
                    .run(
                            ctx -> {
                                assertThat(ctx).as(c.name()).hasNotFailed();
                                assertThat(ctx.getBeansOfType(type))
                                        .as(c.name())
                                        .hasSize(1)
                                        .containsValue(theirs);
                            });
        }
        PromptRegistry prompts = new io.github.llm4j.agent.prompt.MarkdownFolderPromptRegistry(dir);
        runner().withBean(PromptRegistry.class, () -> prompts)
                .withPropertyValues("llm4j.prompts.location=classpath:prompts/")
                .run(ctx -> assertThat(ctx.getBean(PromptRegistry.class)).isSameAs(prompts));
        var skills = new Llm4jAgentsAutoConfiguration.LoadedSkills(List.of());
        runner().withBean(
                        "llm4jSkills",
                        Llm4jAgentsAutoConfiguration.LoadedSkills.class,
                        () -> skills)
                .withPropertyValues("llm4j.skills.locations=classpath:skills/refunds.md")
                .run(
                        ctx ->
                                assertThat(
                                                ctx.getBean(
                                                        Llm4jAgentsAutoConfiguration.LoadedSkills
                                                                .class))
                                        .isSameAs(skills));
        runner().withPropertyValues("llm4j.loom.script=classpath:scripts/three-steps.loom")
                .run(
                        ctx -> {
                            LoomRuntime ours = ctx.getBean(LoomRuntime.class);
                            runner().withBean(LoomRuntime.class, () -> ours)
                                    .withPropertyValues(
                                            "llm4j.loom.script=classpath:scripts/three-steps.loom")
                                    .run(
                                            c2 ->
                                                    assertThat(c2.getBeansOfType(LoomRuntime.class))
                                                            .hasSize(1)
                                                            .containsValue(ours));
                        });
    }

    @Tag("SB-V0.4")
    @Test
    void settingEverythingThatDoesNotStartWorkContactsNoOneAndStartsNothing() throws Exception {
        try (var provider = new Fixtures.Provider("ollama");
                var gemini = new Fixtures.Provider("gemini")) {
            InMemorySecretStore secrets = new InMemorySecretStore();
            secrets.put("EMBED_KEY", "k");
            long childrenBefore = ProcessHandle.current().children().count();
            runner().withBean(SecretStore.class, () -> secrets)
                    .withPropertyValues(
                            "llm4j.clients.default.provider=ollama",
                            "llm4j.clients.default.model=m",
                            "llm4j.clients.default.base-url=" + provider.url(),
                            "llm4j.pricing.models.m.input-per-million=1",
                            "llm4j.pricing.models.m.output-per-million=1",
                            "llm4j.budget.tokens=1000",
                            "llm4j.audit.file=" + dir.resolve("audit.jsonl"),
                            "llm4j.privacy.mask-pii=true",
                            "llm4j.memory.conversations=memory",
                            "llm4j.prompts.location=classpath:prompts/",
                            "llm4j.skills.locations=classpath:skills/refunds.md",
                            "llm4j.tools.builtin=calculator",
                            "llm4j.rag.embedding=gemini",
                            "llm4j.rag.api-key-secret=EMBED_KEY",
                            "llm4j.rag.base-url=" + gemini.url(),
                            "llm4j.rag.store=memory",
                            "llm4j.loom.script=classpath:scripts/three-steps.loom",
                            "llm4j.loom.persistence=file",
                            "llm4j.loom.dir=" + dir.resolve("store"))
                    .run(
                            ctx -> {
                                assertThat(ctx).hasNotFailed();
                                assertThat(provider.seen).isEmpty();
                                assertThat(gemini.seen).isEmpty();
                                assertThat(ProcessHandle.current().children().count())
                                        .isEqualTo(childrenBefore);
                                try (Socket s = new Socket()) {
                                    s.connect(new InetSocketAddress("127.0.0.1", 8787), 200);
                                    org.junit.jupiter.api.Assertions.fail(
                                            "something is listening on 8787");
                                } catch (java.io.IOException expected) {
                                    // nothing is: no page was opened
                                }
                                assertThat(Thread.getAllStackTraces().keySet())
                                        .noneMatch(
                                                t ->
                                                        t.isAlive()
                                                                && t.getName().contains("trigger"));
                            });
        }
    }

    @Tag("SB-V0.5")
    @Test
    void theTwoSettingsThatStartWorkStartExactlyThat() {
        RecordingObserveProvider.reset();
        runner().withPropertyValues(
                        "llm4j.loom.script=classpath:scripts/three-steps.loom",
                        "llm4j.loom.persistence=file",
                        "llm4j.loom.dir=" + dir.resolve("a"),
                        "llm4j.loom.triggers.enabled=true",
                        "llm4j.loom.triggers.poll-interval=50ms")
                .run(
                        ctx -> {
                            assertThat(Thread.getAllStackTraces().keySet())
                                    .anyMatch(t -> t.isAlive() && t.getName().contains("trigger"));
                            assertThat(RecordingObserveProvider.OPENED).isEmpty();
                        });
        RecordingObserveProvider.reset();
        runner().withPropertyValues("llm4j.observe.enabled=true", "llm4j.observe.port=0")
                .run(
                        ctx -> {
                            assertThat(RecordingObserveProvider.OPENED).hasSize(1);
                            assertThat(Thread.getAllStackTraces().keySet())
                                    .noneMatch(t -> t.isAlive() && t.getName().contains("trigger"));
                        });
    }
}
