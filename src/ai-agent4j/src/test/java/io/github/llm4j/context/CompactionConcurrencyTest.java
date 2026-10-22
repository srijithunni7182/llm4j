package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.ReActAgent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Agents that share a strategy instance and a catalogue do not disturb one another (R23). */
class CompactionConcurrencyTest {

    private static String run(ContextCompactor shared, ContextWindows windows) {
        ScriptedModel model = ReActAgentCompactionNativeTest.looping(40);
        ReActAgent.Builder b =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(ReActAgentCompactionNativeTest.search(1_200))
                        .maxIterations(500)
                        .budgetModel("acme/small")
                        .contextWindows(windows)
                        .compaction(shared);
        b.build().run("Q?");
        return RequestRenderer.render(model.requests);
    }

    @Test
    void manyAgentsAtOnceGiveWhatOneAgentAloneGives() throws Exception {
        ContextWindows windows =
                ContextWindows.empty()
                        .with(
                                "acme/small",
                                ContextWindow.of(ReActAgentCompactionNativeTest.WINDOW));
        ContextCompactor shared =
                ContextCompactors.chain(
                        ContextCompactors.trimToolResults(600), ContextCompactors.dropOldest());
        String alone = run(shared, windows);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 48; i++)
                futures.add(pool.submit((Callable<String>) () -> run(shared, windows)));
            for (Future<String> f : futures) assertThat(f.get()).isEqualTo(alone);
        } finally {
            pool.shutdownNow();
        }
    }
}
