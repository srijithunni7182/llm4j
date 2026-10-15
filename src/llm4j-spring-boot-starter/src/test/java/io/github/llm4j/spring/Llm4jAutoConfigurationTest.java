package io.github.llm4j.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.ToolRegistry;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class Llm4jAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(Llm4jAutoConfiguration.class));

    private static Tool tool(String name) {
        return new Tool() {
            public String getName() { return name; }
            public String getDescription() { return name; }
            public String execute(Map<String, Object> args) { return name; }
        };
    }

    @Test
    void addsNothingYouDidNotAskFor() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ToolRegistry.class);
            assertThat(ctx).doesNotHaveBean(LLMClient.class);
            assertThat(ctx).doesNotHaveBean(LoomScript.class);
            assertThat(ctx).doesNotHaveBean(LoomRuntime.class);
            assertThat(ctx.getBean(ToolRegistry.class).getAllTools()).isEmpty();
        });
    }

    @Test
    void switchedOffCreatesNoBeans() {
        runner.withPropertyValues("llm4j.enabled=false", "llm4j.provider=ollama").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(ToolRegistry.class);
            assertThat(ctx).doesNotHaveBean(LLMClient.class);
        });
    }

    @Test
    void toolBeansLandInTheRegistry() {
        runner.withBean("a", Tool.class, () -> tool("alpha")).withBean("b", Tool.class, () -> tool("beta")).run(ctx -> {
            ToolRegistry registry = ctx.getBean(ToolRegistry.class);
            assertThat(registry.getTool("alpha")).isNotNull();
            assertThat(registry.getTool("beta")).isNotNull();
        });
    }

    @Test
    void yourOwnRegistryWins() {
        ToolRegistry mine = new ToolRegistry();
        runner.withBean(ToolRegistry.class, () -> mine).run(ctx -> assertThat(ctx.getBean(ToolRegistry.class)).isSameAs(mine));
    }

    @Test
    void ollamaClientNeedsNoKey() {
        runner.withPropertyValues("llm4j.provider=OLLAMA", "llm4j.model=llama3", "llm4j.base-url=http://localhost:11434")
                .run(ctx -> assertThat(ctx).hasSingleBean(LLMClient.class));
    }

    @Test
    void aProviderThatNeedsAKeyFailsToStartWithoutOne() {
        runner.withPropertyValues("llm4j.provider=anthropic").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseMessage(
                    "llm4j.provider=anthropic needs llm4j.api-key (set it from an environment variable, e.g. llm4j.api-key=${ANTHROPIC_API_KEY})");
        });
    }

    @Test
    void anUnknownProviderIsNamed() {
        runner.withPropertyValues("llm4j.provider=nope", "llm4j.api-key=x").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure().getMessage() + ctx.getStartupFailure().getCause()).contains("nope");
        });
    }

    @Test
    void everyProviderBuilds() {
        for (String p : new String[] {"gemini", "anthropic", "sarvam"}) {
            runner.withPropertyValues("llm4j.provider=" + p, "llm4j.api-key=k", "llm4j.model=m", "llm4j.timeout=30s", "llm4j.connect-timeout=5s", "llm4j.base-url=http://localhost:1")
                    .run(ctx -> assertThat(ctx).hasSingleBean(LLMClient.class));
        }
    }

    @Test
    void yourOwnClientWins() {
        LLMClient mine = new LLMClient() {
            public io.github.llm4j.model.LLMResponse chat(io.github.llm4j.model.LLMRequest r) { return null; }
            public java.util.stream.Stream<io.github.llm4j.model.LLMResponse> chatStream(io.github.llm4j.model.LLMRequest r) { return null; }
        };
        runner.withPropertyValues("llm4j.provider=ollama").withBean(LLMClient.class, () -> mine)
                .run(ctx -> assertThat(ctx.getBean(LLMClient.class)).isSameAs(mine));
    }

    @Test
    void ollamaAddressGetsItsApiPath() {
        assertThat(LLMClients.ollamaApi("http://h:11434/")).isEqualTo("http://h:11434/api");
        assertThat(LLMClients.ollamaApi("http://h:11434/api")).isEqualTo("http://h:11434/api");
    }

    @Test
    void loomBeansAppearWhenAScriptIsConfigured() {
        runner.withPropertyValues("llm4j.loom.script=classpath:hello.loom", "GEMINI_API_KEY=test-key")
                .withBean("t", Tool.class, () -> tool("alpha"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(LoomScript.class).hasSingleBean(LLMClientFactory.class).hasSingleBean(LoomRuntime.class);
                    assertThat(ctx.getBean(io.github.llm4j.loom.execution.ToolRegistry.class).getTool("alpha")).isNotNull();
                    LoomRuntime runtime = ctx.getBean(LoomRuntime.class);
                    assertThat(runtime.script()).isSameAs(ctx.getBean(LoomScript.class));
                    HarnessExecutor a = runtime.newExecutor();
                    assertThat(runtime.newExecutor()).isNotSameAs(a); // one executor for each run
                });
    }

    @Test
    void theEnvironmentSuppliesModelKeysAndAnExecutorCustomizerIsApplied() {
        int[] customized = {0};
        runner.withPropertyValues("llm4j.loom.script=classpath:hello.loom", "GEMINI_API_KEY=test-key")
                .withBean(LoomExecutorCustomizer.class, () -> e -> customized[0]++)
                .run(ctx -> {
                    ctx.getBean(LoomRuntime.class).newExecutor();
                    assertThat(customized[0]).isEqualTo(1);
                });
    }

    @Test
    void aScriptThatCannotRunFailsWhenAnExecutorIsMadeNotMidRun() {
        // no GEMINI_API_KEY: the script's model has no key
        runner.withPropertyValues("llm4j.loom.script=classpath:hello.loom").run(ctx -> {
            assertThat(ctx).hasNotFailed(); // the application still starts
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> ctx.getBean(LoomRuntime.class).newExecutor())
                    .hasMessageContaining("GEMINI_API_KEY");
        });
    }

    @Test
    void aMissingScriptOrLootFileIsNamed() {
        runner.withPropertyValues("llm4j.loom.script=classpath:nope.loom").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(String.valueOf(ctx.getStartupFailure().getCause())).contains("nope.loom");
        });
        runner.withPropertyValues("llm4j.loom.script=classpath:hello.loom", "llm4j.loom.loot=classpath:nope.loot").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(String.valueOf(ctx.getStartupFailure().getCause())).contains("nope.loot");
        });
    }

    @Test
    void aLootFileAddsToolsAndTheirDeclaredReach() {
        runner.withPropertyValues("llm4j.loom.script=classpath:hello.loom", "llm4j.loom.loot=classpath:tools.loot", "GEMINI_API_KEY=k").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(io.github.llm4j.loom.execution.ToolRegistry.class).getTool("Echo")).isNotNull();
        });
    }

    @Test
    void yourOwnScriptWinsAndNeedsNoProperty() {
        runner.withPropertyValues("GEMINI_API_KEY=k")
                .withBean(LoomScript.class, () -> {
                    try { return new io.github.llm4j.loom.execution.LoomLoader().load(
                            java.nio.file.Path.of(getClass().getResource("/hello.loom").toURI()).toString()); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                })
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(LoomScript.class));
    }

    @Test
    void withoutLoomOnTheClassPathTheRestStillWorks() {
        runner.withClassLoader(new FilteredClassLoader("io.github.llm4j.loom")).withPropertyValues("llm4j.loom.script=classpath:hello.loom", "llm4j.provider=ollama")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(LLMClient.class).hasSingleBean(ToolRegistry.class);
                    assertThat(ctx).doesNotHaveBean("loomRuntime");
                });
    }

    @Test
    void runAndStartExecuteAWorkflow() throws Exception {
        runner.withPropertyValues("llm4j.loom.script=classpath:noop.loom").run(ctx -> {
            LoomRuntime runtime = ctx.getBean(LoomRuntime.class);
            runtime.run("Hello", Map.of("name", "world"));
            runtime.run("Hello", null);
            io.github.llm4j.loom.execution.LoomRun run = runtime.start("Hello", Map.of("name", "again"));
            assertThat(run.await(java.time.Duration.ofSeconds(10))).isTrue();
            assertThat(run.status()).isEqualTo(io.github.llm4j.loom.execution.LoomRun.Status.COMPLETED);
        });
    }
}
