package io.github.llm4j.eval.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.eval.judge.LlmJudgeCondition;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestingToolsTest {

    private static final LLMRequest REQ =
            LLMRequest.builder().messages(List.of(Message.user("hi"))).build();

    private static LLMClient usage(int in, int out) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest r) {
                return LLMResponse.builder().content("x").tokenUsage(in, out, in + out).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest r) {
                return Stream.of(chat(r));
            }
        };
    }

    private static Path prices(Path dir) throws Exception {
        Path f = dir.resolve("prices.properties");
        Files.writeString(f, "gemini-3.5-flash = 1.50, 9.00\nfree-model = 0, 0\n");
        return f;
    }

    @Test
    void spendGuardPricesCallsAndStopsAtTheCap(@TempDir Path dir) throws Exception {
        SpendGuard g = SpendGuard.withPrices(prices(dir)).cap(1.0);
        LLMClient c = g.guard(usage(0, 10_000), "gemini-3.5-flash"); // $0.09 a call
        int made = 0;
        while (!g.stopped() && made < 100) {
            c.chat(REQ);
            made++;
        }
        assertThat(g.reason()).contains("cap");
        assertThat(made).isBetween(10, 13);
        assertThatThrownBy(() -> c.chat(REQ)).isInstanceOf(SpendGuard.SpendStopped.class);
        assertThat(g.prices("gemini-3.5-flash")).isTrue();
        assertThat(g.prices("unknown-model")).isFalse();
    }

    @Test
    void aFreeModelCostsNothingAndStageCeilingsTripBeforeTheCap(@TempDir Path dir)
            throws Exception {
        SpendGuard free = SpendGuard.withPrices(prices(dir)).cap(0.01);
        free.guard(usage(1_000_000, 1_000_000), "free-model").chat(REQ);
        assertThat(free.spentUsd()).isZero();
        assertThat(free.stopped()).isFalse();

        SpendGuard g = SpendGuard.withPrices(prices(dir)).cap(10).stage("reasoning", 0.5);
        LLMClient c = g.guard(usage(0, 10_000), "gemini-3.5-flash");
        for (int i = 0; i < 6 && !g.stopped(); i++) {
            c.chat(REQ);
        }
        assertThat(g.reason()).contains("stage 'reasoning'");
    }

    @Test
    void aRunawayGenerationStopsTheRun(@TempDir Path dir) throws Exception {
        SpendGuard g = SpendGuard.withPrices(prices(dir)).maxOutputTokensPerCall(20_000);
        g.guard(usage(10, 25_000), "gemini-3.5-flash").chat(REQ);
        assertThat(g.reason()).contains("25000 output tokens");
    }

    @Test
    void scriptedClientAnswersByRuleAndRecordsRequests() {
        ScriptedClient m =
                new ScriptedClient()
                        .whenSeen("Round 1", ScriptedClient.reactFinal("It does not exist."))
                        .otherwise(ScriptedClient.reactFinal("ok"));
        assertThat(
                        m.chat(
                                        LLMRequest.builder()
                                                .messages(List.of(Message.user("Round 1: go")))
                                                .build())
                                .getContent())
                .contains("It does not exist.");
        assertThat(m.chat(REQ).getContent()).contains("\"final_answer\": \"ok\"");
        assertThat(m.calls()).isEqualTo(2);
        assertThat(ScriptedClient.reactCall("WebSearch", "{\"query\": \"x\"}"))
                .contains("\"action\": \"WebSearch\"");
    }

    @Test
    void fakeJudgeLetsAJudgedCheckRunForFree() {
        boolean ok =
                LlmJudgeCondition.llmJudged("Correctness")
                        .criteria("c")
                        .judge(FakeJudge.rating(5))
                        .threshold(0.7)
                        .build()
                        .matches("anything");
        assertThat(ok).isTrue();
        assertThat(FakeJudge.varied().chat(REQ).getContent()).contains("rating");
    }

    @Test
    void agentReplayPaysOnlyOnceForTheSameCase(@TempDir Path dir) {
        AgentReplay replay = AgentReplay.at(dir);
        AtomicInteger runs = new AtomicInteger();
        String key = AgentReplay.key("alex-01", "v1", "gemini", "CURRENT TIME: 10:00:00\nfixture");
        String sameKeyLaterClock =
                AgentReplay.key("alex-01", "v1", "gemini", "CURRENT TIME: 10:00:09\nfixture");
        assertThat(key)
                .isEqualTo(sameKeyLaterClock)
                .isNotEqualTo(AgentReplay.key("alex-01", "v2", "gemini", "fixture"));
        java.util.function.Supplier<AgentResult> run =
                () -> {
                    runs.incrementAndGet();
                    return AgentResult.builder()
                            .finalAnswer("done")
                            .completed(true)
                            .iterations(2)
                            .addStep(
                                    new AgentResult.AgentStep(
                                            "t",
                                            "WebSearch",
                                            "{\"query\":\"q\"}",
                                            "No results found."))
                            .build();
                };
        AgentResult first = replay.run(key, run);
        AgentResult second = replay.run(sameKeyLaterClock, run);
        assertThat(runs.get()).isEqualTo(1);
        assertThat(second.getFinalAnswer()).isEqualTo(first.getFinalAnswer());
        assertThat(second.getSteps()).hasSize(1);
        assertThat(second.getSteps().get(0).getAction()).isEqualTo("WebSearch");
        assertThat(second.isCompleted()).isTrue();
    }

    @Test
    void recordedSearchAnswersFromTheLibraryAndFindsNothingForFabricatedTerms(@TempDir Path dir)
            throws Exception {
        Path lib = dir.resolve("search.yaml");
        Files.writeString(
                lib,
                "- id: pqc\n  match: 'post.?quantum|ml-kem'\n  snippets:\n  - 'FIPS 203 standardizes ML-KEM.'\n");
        RecordedSearchTool t = RecordedSearchTool.fromYaml("WebSearch", lib).suppressing("QLL-7");
        assertThat(t.execute(Map.of("query", "ML-KEM TLS"))).contains("FIPS 203");
        assertThat(t.execute(Map.of("query", "QLL-7 ml-kem")))
                .isEqualTo(RecordedSearchTool.NO_RESULTS);
        assertThat(t.execute(Map.of("query", "something else")))
                .isEqualTo(RecordedSearchTool.NO_RESULTS);
        assertThat(t.calls()).isEqualTo(3);
        assertThat(
                        RecordedSearchTool.fixed("WebSearch", List.of("own"))
                                .execute(Map.of("query", "x")))
                .contains("own");
    }
}
