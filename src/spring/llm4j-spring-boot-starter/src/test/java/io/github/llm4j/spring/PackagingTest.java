package io.github.llm4j.spring;

import static io.github.llm4j.spring.ClientsTest.rootConfigurationProblem;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.LLMClientFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** One artifact, optional modules, and the messages that name what to add. V1. */
class PackagingTest {

    @Tag("SB-V1.1")
    @Test
    void theOptionalModulesAreOptionalAndNothingImportsSpringBootsBom() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        for (String artifact :
                List.of(
                        "ai-agent4j-loom",
                        "ai-agent4j-tools",
                        "ai-agent4j-addons",
                        "spring-jdbc",
                        "spring-boot-configuration-processor")) {
            int at = pom.indexOf("<artifactId>" + artifact + "</artifactId>");
            assertThat(at).as(artifact).isGreaterThan(0);
            String dependency = pom.substring(at, pom.indexOf("</dependency>", at));
            assertThat(dependency).as(artifact).contains("<optional>true</optional>");
        }
        assertThat(pom).doesNotContain("spring-boot-dependencies");
        assertThat(pom).doesNotContain("<scope>import</scope>");
    }

    @Tag("SB-V1.2")
    @Test
    void settingsForLoomWithoutLoomNameTheArtifactToAdd() {
        Fixtures.runner()
                .withClassLoader(new FilteredClassLoader("io.github.llm4j.loom"))
                .withPropertyValues("llm4j.loom.script=classpath:scripts/three-steps.loom")
                .run(
                        ctx -> {
                            assertThat(ctx).hasFailed();
                            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).action())
                                    .contains("ai-agent4j-loom");
                        });
    }

    @Tag("SB-V1.3")
    @Test
    void everyOtherOptionalModuleIsNamedToo() {
        Fixtures.runner()
                .withClassLoader(
                        new FilteredClassLoader("io.github.llm4j.agent.rag.store.PGVectorStore"))
                .withPropertyValues("llm4j.rag.store=pgvector")
                .run(
                        ctx ->
                                assertThat(
                                                rootConfigurationProblem(ctx.getStartupFailure())
                                                        .action())
                                        .contains("ai-agent4j-addons"));
        Fixtures.runner()
                .withClassLoader(new FilteredClassLoader("io.github.llm4j.loom.observe.Observe"))
                .withPropertyValues("llm4j.observe.enabled=true")
                .run(
                        ctx ->
                                assertThat(
                                                rootConfigurationProblem(ctx.getStartupFailure())
                                                        .action())
                                        .contains("loom-inspect"));
        Fixtures.runner()
                .withClassLoader(new FilteredClassLoader("io.github.llm4j.tools"))
                .withPropertyValues("llm4j.tools.custom.X.kind=file")
                .run(
                        ctx ->
                                assertThat(
                                                rootConfigurationProblem(ctx.getStartupFailure())
                                                        .action())
                                        .contains("ai-agent4j-tools"));
        Fixtures.runner()
                .withClassLoader(new FilteredClassLoader("org.springframework.jdbc"))
                .withPropertyValues(
                        "llm4j.loom.script=classpath:scripts/three-steps.loom",
                        "llm4j.loom.persistence=jdbc")
                .run(
                        ctx ->
                                assertThat(
                                                rootConfigurationProblem(ctx.getStartupFailure())
                                                        .action())
                                        .contains("spring-jdbc"));
        Fixtures.runner()
                .withPropertyValues(
                        "llm4j.loom.script=classpath:scripts/three-steps.loom",
                        "llm4j.loom.persistence=jdbc")
                .withBean(LLMClientFactory.class, LoomFixtures.Models::new)
                .run(
                        ctx -> {
                            assertThat(ctx).hasFailed();
                            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).action())
                                    .contains("spring.datasource");
                        });
    }

    @Tag("SB-V1.4")
    @Test
    void theOldPropertiesAreTheClientNamedDefault() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            Fixtures.runner()
                    .withBean(LLMClientFactory.class, LoomFixtures.Models::new)
                    .withPropertyValues(
                            "llm4j.provider=ollama",
                            "llm4j.model=llama3",
                            "llm4j.base-url=" + p.url(),
                            "llm4j.loom.script=classpath:scripts/three-steps.loom")
                    .run(
                            ctx -> {
                                assertThat(ctx.getBean(LLMClients.class).names())
                                        .containsExactly("default");
                                assertThat(ctx).hasSingleBean(LoomRuntime.class);
                                assertThat(
                                                Fixtures.ask(
                                                                ctx.getBean(
                                                                        io.github.llm4j.LLMClient
                                                                                .class),
                                                                "hi")
                                                        .getContent())
                                        .isEqualTo("hello");
                            });
        }
    }

    @Tag("SB-V1.5")
    @Test
    void aShorthandAndItsLongFormTogetherAreRefused() {
        Fixtures.runner()
                .withPropertyValues(
                        "llm4j.provider=ollama", "llm4j.clients.default.provider=gemini")
                .run(
                        ctx -> {
                            assertThat(ctx).hasFailed();
                            assertThat(rootConfigurationProblem(ctx.getStartupFailure()))
                                    .hasMessageContaining("llm4j.provider")
                                    .hasMessageContaining("llm4j.clients.default");
                        });
    }

    @Tag("SB-V1.6")
    @Test
    void eachAutoConfigurationCanBeLeftOutOnItsOwn() {
        List<Class<?>> all =
                List.of(
                        Llm4jGuardAutoConfiguration.class,
                        Llm4jSecretsAutoConfiguration.class,
                        Llm4jClientsAutoConfiguration.class,
                        Llm4jToolsAutoConfiguration.class,
                        Llm4jAgentsAutoConfiguration.class,
                        Llm4jRagAutoConfiguration.class,
                        Llm4jObserveAutoConfiguration.class,
                        Llm4jLoomAutoConfiguration.class);
        for (Class<?> left : all) {
            Class<?>[] rest = all.stream().filter(c -> c != left).toArray(Class<?>[]::new);
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(rest))
                    .run(
                            ctx ->
                                    assertThat(ctx)
                                            .as("without " + left.getSimpleName())
                                            .hasNotFailed());
        }
    }

    @Tag("SB-V1.7")
    @Test
    void oneJacksonVersionIsOnTheClassPath() throws Exception {
        String root = Files.readString(Path.of("../../../pom.xml"));
        String wanted = root.replaceAll("(?s).*<jackson.version>([^<]+)</jackson.version>.*", "$1");
        // llm4j's modules agree on one Jackson through the parent's setting; the starter adds
        // nothing of its own
        assertThat(com.fasterxml.jackson.core.json.PackageVersion.VERSION.toString())
                .isEqualTo(wanted);
    }
}
