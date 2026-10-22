package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.exception.ContextOverflowException;
import io.github.llm4j.model.LLMRequest;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * An agent whose prompt a provider rejects as too long compacts harder, once, and tries again once
 * (R15); and refuses a hopeless call (R16).
 */
class ContextOverflowRecoveryTest {

    private static final ContextOverflowException OVERFLOW =
            new ContextOverflowException(
                    "HTTP 400: prompt is too long: 5000 tokens > 4000 maximum", 400, "{}");

    private static IntFunction<Object> nativeScript(int steps, int... overflowAt) {
        return n -> {
            for (int o : overflowAt) if (n == o) return OVERFLOW;
            int step = n - (int) java.util.Arrays.stream(overflowAt).filter(o -> o < n).count();
            return step < steps
                    ? ScriptedModel.calls(
                            "s",
                            ScriptedModel.call("c" + step, "search", Map.of("q", "query " + step)))
                    : ScriptedModel.answer("done");
        };
    }

    private static IntFunction<Object> textScript(int steps, int... overflowAt) {
        return n -> {
            for (int o : overflowAt) if (n == o) return OVERFLOW;
            int step = n - (int) java.util.Arrays.stream(overflowAt).filter(o -> o < n).count();
            return step < steps
                    ? ScriptedModel.textStep("t", "search", "{\"q\":\"query " + step + "\"}")
                    : ScriptedModel.textAnswer("done");
        };
    }

    // a policy whose window the scripted model does not enforce, so only the scripted overflow
    // happens
    private static ContextPolicy roomyPolicy() {
        return ContextPolicy.builder().window(ContextWindow.of(1_000_000)).keepRecent(2).build();
    }

    @Test
    void theNativeLoopCompactsHarderAndRetriesOnce() {
        ScriptedModel model = ScriptedModel.by(true, true, nativeScript(12, 8));
        ContextListener listener = new ContextListener();
        AgentResult r =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(ReActAgentCompactionNativeTest.search(1_200))
                        .maxIterations(100)
                        .contextPolicy(roomyPolicy())
                        .compaction(ContextCompactors.dropOldest())
                        .addListener(listener)
                        .build()
                        .run("Q?");

        assertThat(r.getFinalAnswer()).isEqualTo("done");
        assertThat(listener.compactions).hasSize(1);
        CompactionEvent e = listener.compactions.get(0);
        assertThat(e.reason()).isEqualTo(CompactionRequest.Reason.OVERFLOW);
        assertThat(e.result().outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
        // the rejected request and its retry: the retry is smaller
        LLMRequest rejected = model.requests.get(8);
        LLMRequest retry = model.requests.get(9);
        assertThat(ReActAgentCompactionNativeTest.size(retry))
                .isLessThan(ReActAgentCompactionNativeTest.size(rejected) / 2 + 200);
        assertThat(retry.getMessages().size()).isLessThan(rejected.getMessages().size());
        ReActAgentCompactionNativeTest.assertPairsWhole(retry);
        assertThat(model.requests)
                .hasSize(
                        8 + 1 + (12 - 8)
                                + 1); // 8 steps, the rejected call, the retry (which is step 9) and
        // the rest, the answer
    }

    @Test
    void theTextLoopCompactsHarderAndRetriesOnce() {
        ScriptedModel model = ScriptedModel.by(false, true, textScript(12, 8));
        ContextListener listener = new ContextListener();
        AgentResult r =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(ReActAgentCompactionNativeTest.search(1_200))
                        .maxIterations(100)
                        .contextPolicy(roomyPolicy())
                        .compaction(ContextCompactors.dropOldest())
                        .addListener(listener)
                        .build()
                        .run("Q?");
        assertThat(r.getFinalAnswer()).isEqualTo("done");
        assertThat(listener.compactions).hasSize(1);
        assertThat(listener.compactions.get(0).reason())
                .isEqualTo(CompactionRequest.Reason.OVERFLOW);
        assertThat(ReActAgentCompactionTextTest.prompt(model.requests.get(9)).length())
                .isLessThan(ReActAgentCompactionTextTest.prompt(model.requests.get(8)).length());
        assertThat(ReActAgentCompactionTextTest.prompt(model.requests.get(9)))
                .contains("Earlier steps: [");
    }

    @Test
    void aSecondOverflowFailsTheRunWithThatException() {
        ScriptedModel model =
                ScriptedModel.by(
                        true,
                        true,
                        n ->
                                n < 8
                                        ? ScriptedModel.calls(
                                                "s",
                                                ScriptedModel.call(
                                                        "c" + n,
                                                        "search",
                                                        Map.of("q", "query " + n)))
                                        : OVERFLOW);
        ContextListener listener = new ContextListener();
        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(ReActAgentCompactionNativeTest.search(1_200))
                        .maxIterations(100)
                        .contextPolicy(roomyPolicy())
                        .compaction(ContextCompactors.dropOldest())
                        .addListener(listener)
                        .build();
        assertThatThrownBy(() -> agent.run("Q?")).isSameAs(OVERFLOW);
        assertThat(model.requests).hasSize(10); // 8 steps, the rejected call, one retry, no more
        assertThat(listener.compactions).hasSize(1);
    }

    @Test
    void withoutACompactorTheProvidersErrorPropagatesAsItAlwaysDid() {
        ScriptedModel model = ScriptedModel.by(true, true, nativeScript(12, 3));
        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(ReActAgentCompactionNativeTest.search(400))
                        .maxIterations(100)
                        .addListener(new ContextListener())
                        .build();
        assertThatThrownBy(() -> agent.run("Q?")).isSameAs(OVERFLOW);
        assertThat(model.requests).hasSize(4);
    }

    @Test
    void whenToldToFailItDoesNotRecover() {
        ScriptedModel model = ScriptedModel.by(true, true, nativeScript(12, 5));
        ContextPolicy policy =
                ContextPolicy.builder()
                        .window(ContextWindow.of(1_000_000))
                        .keepRecent(2)
                        .onOverflow(ContextPolicy.OnOverflow.FAIL)
                        .build();
        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(ReActAgentCompactionNativeTest.search(400))
                        .maxIterations(100)
                        .contextPolicy(policy)
                        .compaction(ContextCompactors.dropOldest())
                        .build();
        assertThatThrownBy(() -> agent.run("Q?")).isSameAs(OVERFLOW);
        assertThat(model.requests).hasSize(6);
    }

    @Test
    void whenNothingCanBeShrunkTheOriginalErrorIsTheOne() {
        // only the task is in the conversation, so there is nothing to remove
        ScriptedModel model = ScriptedModel.by(true, true, n -> OVERFLOW);
        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(ReActAgentCompactionNativeTest.search(400))
                        .maxIterations(100)
                        .contextPolicy(roomyPolicy())
                        .compaction(ContextCompactors.dropOldest())
                        .build();
        assertThatThrownBy(() -> agent.run("Q?")).isSameAs(OVERFLOW);
        assertThat(model.requests).hasSize(1);
    }

    @Test
    void anOverflowRecoveryWithASummaryCountsTheSummariesInTheUsage() {
        SummariserModel summariser = SummariserModel.saying("Notes.");
        ScriptedModel model = ScriptedModel.by(true, true, nativeScript(12, 8));
        AgentResult r =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(ReActAgentCompactionNativeTest.search(1_200))
                        .maxIterations(100)
                        .contextPolicy(roomyPolicy())
                        .compaction(ContextCompactors.summarize(summariser))
                        .build()
                        .run("Q?");
        assertThat(summariser.requests).hasSize(1);
        assertThat(r.getUsage().getLlmCalls())
                .isEqualTo(model.requests.size() - 1 /* the rejected call reported no usage */ + 1);
    }

    // ── a prompt that cannot fit (R16) ─────────────────────────────────────────────

    @Test
    void aPromptFarOverTheWindowIsRefusedBeforeTheProviderIsAsked() {
        ScriptedModel model = ReActAgentCompactionNativeTest.looping(5);
        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(ReActAgentCompactionNativeTest.search(400))
                        .maxIterations(100)
                        .contextWindow(40)
                        .build();
        assertThatThrownBy(() -> agent.run("Q?"))
                .isInstanceOf(ContextOverflowException.class)
                .hasMessageContaining("cannot fit");
        assertThat(model.requests).isEmpty();
    }

    @Test
    void theSameInTheTextLoop() {
        ScriptedModel model = ReActAgentCompactionTextTest.looping(5);
        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(ReActAgentCompactionNativeTest.search(400))
                        .maxIterations(100)
                        .contextWindow(40)
                        .build();
        assertThatThrownBy(() -> agent.run("Q?")).isInstanceOf(ContextOverflowException.class);
        assertThat(model.requests).isEmpty();
    }

    @Test
    void aRunThatGrowsPastTheWindowStopsClearlyWithAnUnhelpfulCompactor() {
        ScriptedModel model = ReActAgentCompactionNativeTest.looping(40);
        ContextCompactor nothing = (m, q) -> CompactionResult.of("nothing", m, m, q);
        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(model)
                        .addTool(ReActAgentCompactionNativeTest.search(1_200))
                        .maxIterations(100)
                        .contextPolicy(ReActAgentCompactionNativeTest.policy())
                        .compaction(nothing)
                        .build();
        assertThatThrownBy(() -> agent.run("Q?")).isInstanceOf(ContextOverflowException.class);
        // it asked the provider only while the prompt was still plausibly within the window
        for (LLMRequest q : model.requests)
            assertThat(ReActAgentCompactionNativeTest.size(q))
                    .isLessThanOrEqualTo((long) (ReActAgentCompactionNativeTest.WINDOW * 1.25));
    }
}
