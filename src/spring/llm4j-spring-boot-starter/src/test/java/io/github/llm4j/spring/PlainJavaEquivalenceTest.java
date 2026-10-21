package io.github.llm4j.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.host.LoomHost;
import io.github.llm4j.loom.host.Persistence;
import io.github.llm4j.loom.host.RunOutcome;
import io.github.llm4j.loom.host.RunRequest;
import io.github.llm4j.provider.ollama.OllamaProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The same things, written as plain Java (the README's "Without Spring" blocks) and as properties,
 * end in the same place. V0.7.
 */
class PlainJavaEquivalenceTest {

    @TempDir Path dir;

    private static final String FINAL =
            "```json\n{\"thought\": \"t\", \"final_answer\": \"42\"}\n```";

    @Tag("SB-V0.7")
    @Test
    void clientBudgetAgentAndLoomRunBehaveTheSameWithAndWithoutSpring() throws Exception {
        try (var provider = new Fixtures.Provider("ollama")) {
            provider.reply = FINAL;

            // --- with Spring
            long[] spring = new long[3];
            String[] springAnswer = new String[1];
            Fixtures.runner()
                    .withPropertyValues(
                            "llm4j.clients.default.provider=ollama",
                            "llm4j.clients.default.model=m",
                            "llm4j.clients.default.base-url=" + provider.url(),
                            "llm4j.clients.default.retry.enabled=false",
                            "llm4j.budget.tokens=1000",
                            "llm4j.budget.per-call-max-tokens=100")
                    .run(
                            ctx -> {
                                assertThat(ctx).hasNotFailed();
                                springAnswer[0] =
                                        ctx.getBean(ReActAgent.Builder.class)
                                                .build()
                                                .run("what is six times seven")
                                                .getFinalAnswer();
                                io.github.llm4j.budget.Spent spent =
                                        ctx.getBean(Budget.class).spent();
                                spring[0] = spent.calls();
                                spring[1] = spent.promptTokens() + spent.completionTokens();
                            });
            int springRequests = provider.seen.size();
            provider.seen.clear();

            // --- without Spring: what the README shows
            LLMClient inner =
                    new DefaultLLMClient(
                            new OllamaProvider(
                                    LLMConfig.builder()
                                            .defaultModel("m")
                                            .baseUrl(provider.url() + "/api")
                                            .retryPolicy(
                                                    io.github.llm4j.config.RetryPolicy.noRetry())
                                            .build()));
            Budget budget = Budget.builder().tokens(1000).build();
            LLMClient client =
                    BudgetedLLMClient.builder(inner).budget(budget).perCallCap(100).build();
            String plainAnswer =
                    ReActAgent.builder()
                            .llmClient(client)
                            .build()
                            .run("what is six times seven")
                            .getFinalAnswer();
            io.github.llm4j.budget.Spent spent = budget.spent();

            assertThat(plainAnswer).isEqualTo(springAnswer[0]).isEqualTo("42");
            assertThat(spent.calls()).isEqualTo(spring[0]);
            assertThat(spent.promptTokens() + spent.completionTokens()).isEqualTo(spring[1]);
            assertThat(provider.seen).hasSize(springRequests);
        }

        // --- a Loom run
        var springModels = new LoomFixtures.Models();
        RunOutcome[] out = new RunOutcome[1];
        Fixtures.runner()
                .withBean(io.github.llm4j.loom.execution.LLMClientFactory.class, () -> springModels)
                .withPropertyValues(
                        "llm4j.loom.script=classpath:scripts/three-steps.loom",
                        "llm4j.loom.persistence=file",
                        "llm4j.loom.dir=" + dir.resolve("spring"))
                .run(
                        ctx ->
                                out[0] =
                                        ctx.getBean(LoomRuntime.class)
                                                .run(RunRequest.of("Main", Map.of())));

        var plainModels = new LoomFixtures.Models();
        var script =
                new io.github.llm4j.loom.execution.LoomLoader()
                        .load("src/test/resources/scripts/three-steps.loom");
        LoomHost host =
                LoomHost.builder(script, new ToolRegistry(), plainModels)
                        .persistence(Persistence.file(dir.resolve("plain")))
                        .build();
        RunOutcome plain = host.run(RunRequest.of("Main", Map.of()));

        assertThat(out[0]).isInstanceOf(RunOutcome.Completed.class);
        assertThat(plain).isInstanceOf(RunOutcome.Completed.class);
        for (String agent : new String[] {"Researcher", "Drafter", "Reviewer"})
            assertThat(plainModels.calls(agent)).isEqualTo(springModels.calls(agent)).isEqualTo(1);
        assertThat(Files.isDirectory(dir.resolve("plain/runs")))
                .isEqualTo(Files.isDirectory(dir.resolve("spring/runs")))
                .isTrue();
    }
}
