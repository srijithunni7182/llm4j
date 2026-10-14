package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.model.LLMRequest;
import org.junit.jupiter.api.Test;

/** A long run that would overflow without compaction, and finishes with it (A6, R6). */
class CompactionSoakTest {

    @Test
    void threeHundredStepsStayWithinTheWindow() {
        int window = 8_000;
        ScriptedModel model = ReActAgentCompactionNativeTest.looping(300);
        ContextListener listener = new ContextListener();
        ContextPolicy policy = ContextPolicy.builder().window(ContextWindow.of(window)).keepRecent(4).maxCompactions(10_000).build();
        long started = System.nanoTime();
        AgentResult r = ReActAgent.builder().llmClient(model).addTool(ReActAgentCompactionNativeTest.search(3_000)).maxIterations(1_000)
                .contextPolicy(policy)
                .compaction(ContextCompactors.chain(ContextCompactors.trimToolResults(500), ContextCompactors.dropOldest()))
                .addListener(listener).build().run("Go on.");

        assertThat(r.getFinalAnswer()).isEqualTo("done after 300");
        assertThat(model.requests).hasSize(301);
        long worst = model.requests.stream().mapToLong(ReActAgentCompactionNativeTest::size).max().orElseThrow();
        assertThat(worst).isLessThanOrEqualTo(window);
        assertThat(listener.compacted()).isGreaterThan(20);
        assertThat(model.requests.stream().filter(q -> true).count()).isEqualTo(301);
        // a count-based bound, not a time-based one; this is only a sanity limit
        assertThat((System.nanoTime() - started) / 1_000_000_000L).isLessThan(60);
        model.requests.forEach(ReActAgentCompactionNativeTest::assertPairsWhole);
    }
}
