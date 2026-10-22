package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.model.LLMRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * A summarising strategy inside an agent: its model calls are counted in the agent's usage and
 * reported (R13).
 */
class SummarizeAgentTest {

    private static long mainPromptTokens(ScriptedModel model) {
        return model.requests.stream().mapToLong(ReActAgentCompactionNativeTest::size).sum();
    }

    private void assertUsageIncludesTheSummariser(
            ScriptedModel model,
            SummariserModel summariser,
            AgentResult r,
            ContextListener listener) {
        int s = summariser.requests.size();
        assertThat(s).isGreaterThan(0);
        AgentResult.Usage u = r.getUsage();
        assertThat(u.getLlmCalls()).isEqualTo(model.requests.size() + s);
        assertThat(u.getPromptTokens()).isEqualTo(mainPromptTokens(model) + 700L * s);
        assertThat(u.getCompletionTokens()).isEqualTo(20L * model.requests.size() + 60L * s);
        assertThat(u.getTotalTokens()).isEqualTo(u.getPromptTokens() + u.getCompletionTokens());
        assertThat(u.getCost())
                .isEqualByComparingTo(new BigDecimal("0.0021").multiply(BigDecimal.valueOf(s)));
        long reported =
                listener.compactions.stream()
                        .filter(e -> e.result().summarizerUsage() != null)
                        .count();
        assertThat(reported).isEqualTo(s);
    }

    @Test
    void theNativeLoopCountsTheSummarisersTokensAndCost() {
        ScriptedModel model = ReActAgentCompactionNativeTest.looping(30);
        SummariserModel summariser =
                SummariserModel.saying("Looked at 20 pages; the price is 500.");
        ContextListener listener = new ContextListener();
        AgentResult r =
                ReActAgentCompactionNativeTest.agent(
                                model, ReActAgentCompactionNativeTest.search(1_200))
                        .contextPolicy(ReActAgentCompactionNativeTest.policy())
                        .compaction(ContextCompactors.summarize(summariser))
                        .addListener(listener)
                        .build()
                        .run("Q?");
        assertThat(r.getFinalAnswer()).isEqualTo("done after 30");
        assertUsageIncludesTheSummariser(model, summariser, r, listener);
        for (LLMRequest q : model.requests)
            assertThat(ReActAgentCompactionNativeTest.size(q))
                    .isLessThanOrEqualTo(ReActAgentCompactionNativeTest.WINDOW);
        // the summary is what the model reads in place of the old steps
        String last =
                model.requests.get(model.requests.size() - 1).getMessages().stream()
                        .map(m -> m.getContent())
                        .reduce("", (a, b) -> a + "\n" + b);
        assertThat(last).contains("Looked at 20 pages; the price is 500.");
    }

    @Test
    void theTextLoopCountsTheSummarisersTokensAndCost() {
        ScriptedModel model = ReActAgentCompactionTextTest.looping(30);
        SummariserModel summariser =
                SummariserModel.saying("Looked at 20 pages; the price is 500.");
        ContextListener listener = new ContextListener();
        AgentResult r =
                ReActAgentCompactionTextTest.agent(model, 1_200)
                        .contextPolicy(ReActAgentCompactionNativeTest.policy())
                        .compaction(ContextCompactors.summarize(summariser))
                        .addListener(listener)
                        .build()
                        .run("Q?");
        assertThat(r.getFinalAnswer()).isEqualTo("done after 30");
        assertUsageIncludesTheSummariser(model, summariser, r, listener);
        assertThat(
                        ReActAgentCompactionTextTest.prompt(
                                model.requests.get(model.requests.size() - 1)))
                .contains("Earlier steps: Notes on the earlier steps")
                .contains("Looked at 20 pages; the price is 500.");
    }

    @Test
    void aSummariserThatFailsDoesNotFailTheRunAndCostsNothing() {
        ScriptedModel model = ReActAgentCompactionNativeTest.looping(30);
        SummariserModel summariser = SummariserModel.failing(new ProviderException("test", "503"));
        ContextListener listener = new ContextListener();
        AgentResult r =
                ReActAgentCompactionNativeTest.agent(
                                model, ReActAgentCompactionNativeTest.search(1_200))
                        .contextPolicy(ReActAgentCompactionNativeTest.policy())
                        .compaction(ContextCompactors.summarize(summariser))
                        .addListener(listener)
                        .build()
                        .run("Q?");
        assertThat(r.getFinalAnswer()).isEqualTo("done after 30");
        assertThat(r.getUsage().getLlmCalls()).isEqualTo(model.requests.size());
        assertThat(listener.compacted()).isGreaterThan(0);
        assertThat(listener.compactions.get(0).result().note())
                .contains("fell back to drop_oldest");
        for (LLMRequest q : model.requests)
            assertThat(ReActAgentCompactionNativeTest.size(q))
                    .isLessThanOrEqualTo(ReActAgentCompactionNativeTest.WINDOW);
    }

    @Test
    void theRecommendedChainWorksEndToEnd() {
        ScriptedModel model = ReActAgentCompactionNativeTest.looping(40);
        SummariserModel summariser = SummariserModel.saying("Notes.");
        AgentResult r =
                ReActAgentCompactionNativeTest.agent(
                                model, ReActAgentCompactionNativeTest.search(1_500))
                        .contextPolicy(ReActAgentCompactionNativeTest.policy())
                        .compaction(
                                ContextCompactors.chain(
                                        ContextCompactors.trimToolResults(400),
                                        ContextCompactors.summarize(summariser),
                                        ContextCompactors.dropOldest()))
                        .build()
                        .run("Q?");
        assertThat(r.getFinalAnswer()).isEqualTo("done after 40");
        for (LLMRequest q : model.requests)
            assertThat(ReActAgentCompactionNativeTest.size(q))
                    .isLessThanOrEqualTo(ReActAgentCompactionNativeTest.WINDOW);
    }

    @Test
    void anAgentsOwnBudgetedClientCanBeTheSummariserAndItsSpendIsMetered() {
        io.github.llm4j.budget.Budget budget =
                io.github.llm4j.budget.Budget.builder().tokens(1_000_000).build();
        SummariserModel inner = SummariserModel.saying("Notes.");
        io.github.llm4j.LLMClient metered =
                io.github.llm4j.budget.BudgetedLLMClient.builder(inner).budget(budget).build();
        ScriptedModel model = ReActAgentCompactionNativeTest.looping(30);
        ReActAgentCompactionNativeTest.agent(model, ReActAgentCompactionNativeTest.search(1_200))
                .contextPolicy(ReActAgentCompactionNativeTest.policy())
                .compaction(ContextCompactors.summarize(metered))
                .build()
                .run("Q?");
        assertThat(budget.spent().tokens()).isGreaterThan(0);
    }
}
