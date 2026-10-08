package io.github.llm4j.eval.export;

import static io.github.llm4j.eval.judge.LlmJudgeCondition.llmJudged;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.eval.assertions.AgentAssertions;
import io.github.llm4j.eval.judge.InMemoryJudgeCache;
import io.github.llm4j.eval.report.EvalRecorder;
import io.github.llm4j.eval.support.StubJudge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

/** Profiles, budget, pricing and agent traces (EXP-30..46). */
class ProfileTest {

    @TempDir Path root;

    @BeforeEach
    void setUp() {
        EvalRun.resetForTests();
        JudgeStats.reset();
        System.setProperty(ExportConfig.DIR, root.toString());
        System.setProperty(ExportConfig.RUN_ID, "RUN-PROFILE-1");
        EvalRecorder.reset();
        EvalRecorder.activate();
        EvalRun.get().bindTest("com.acme.T", "t()");
    }

    @AfterEach
    void tearDown() {
        for (String k :
                new String[] {
                    ExportConfig.DIR,
                    ExportConfig.RUN_ID,
                    ExportConfig.PROFILE,
                    "eval4j.sample.rate",
                    "eval4j.judge.budgetUsd",
                    "eval4j.pricing"
                }) {
            System.clearProperty(k);
        }
        EvalRun.resetForTests();
        JudgeStats.reset();
        EvalRecorder.reset();
    }

    private List<JsonNode> evals() throws Exception {
        EvalRun.get().finish();
        List<JsonNode> out = new ArrayList<>();
        for (String l : Files.readAllLines(root.resolve("runs/RUN-PROFILE-1/evaluations.jsonl"))) {
            out.add(RunWriter.MAPPER.readTree(l));
        }
        return out;
    }

    private boolean judge(StubJudge judge, InMemoryJudgeCache cache) {
        return llmJudged("Correctness")
                .criteria("c")
                .judge(judge)
                .threshold(0.7)
                .cache(cache)
                .judgeIdentifier("gemini-2.5-pro")
                .build()
                .matches("answer");
    }

    @Test
    void fastProfileNeverCallsTheJudgeAndAbortsInsteadOfPassing() throws Exception {
        System.setProperty(ExportConfig.PROFILE, "FAST");
        StubJudge stub = StubJudge.rating(m -> 5);
        assertThatThrownBy(() -> judge(stub, InMemoryJudgeCache.create()))
                .isInstanceOf(TestAbortedException.class);
        assertThat(stub.callCount()).isZero();
        JsonNode e = evals().get(0);
        assertThat(e.path("status").asText()).isEqualTo("NOT_EVALUATED");
        assertThat(e.has("passed")).isFalse();
    }

    @Test
    void fastProfileStillServesCacheHits() throws Exception {
        StubJudge stub = StubJudge.rating(m -> 5);
        InMemoryJudgeCache cache = InMemoryJudgeCache.create();
        assertThat(judge(stub, cache)).isTrue();
        System.setProperty(ExportConfig.PROFILE, "FAST");
        EvalRun.resetForTests();
        EvalRecorder.reset();
        EvalRecorder.activate();
        EvalRun.get().bindTest("com.acme.T", "t()");
        assertThat(judge(stub, cache)).isTrue();
        assertThat(stub.callCount()).isEqualTo(1);
        List<JsonNode> all = evals();
        assertThat(all.get(all.size() - 1).path("source").asText()).isEqualTo("REUSED");
    }

    @Test
    void sampleSelectionIsDeterministicForASeed() throws Exception {
        System.setProperty(ExportConfig.PROFILE, "SAMPLE");
        System.setProperty("eval4j.sample.rate", "1.0");
        assertThat(judge(StubJudge.rating(m -> 5), InMemoryJudgeCache.create())).isTrue();
        System.setProperty("eval4j.sample.rate", "0.0");
        assertThatThrownBy(() -> judge(StubJudge.rating(m -> 5), InMemoryJudgeCache.create()))
                .isInstanceOf(TestAbortedException.class);
        assertThat(evals()).hasSize(2);
    }

    @Test
    void pricingFeedsCostAndBudgetStopsFurtherJudging() throws Exception {
        Path prices = root.resolve("prices.properties");
        Files.writeString(prices, "gemini-2.5-pro = 1000000, 1000000\n");
        System.setProperty("eval4j.pricing", prices.toString());
        System.setProperty("eval4j.judge.budgetUsd", "0.0000001");
        EvalRun.get().declareJudge("gemini-2-5-pro", "g", "gemini-2.5-pro", 0.0, 1, "MEAN");
        StubJudge stub = StubJudge.rating(m -> 5);
        // the stub reports no tokens, so spend stays zero and the first call is allowed
        assertThat(judge(stub, InMemoryJudgeCache.create())).isTrue();
        JudgeTelemetry.callMade("gemini-2-5-pro", 5, 10, 10, false);
        EvalRecorder.record(
                "Other", 1, 0.5, null, "gemini-2.5-pro"); // carries the 20 tokens, costs $20
        assertThatThrownBy(() -> judge(StubJudge.rating(m -> 5), InMemoryJudgeCache.create()))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("budget");
        assertThat(Pricing.fromSystem().cost("x", "gemini-2.5-pro", 1_000_000, 0))
                .isEqualTo(1000000.0);
        assertThat(Pricing.fromSystem().cost("x", "unknown", 1, 1)).isNull();
        assertThat(evals().stream().anyMatch(n -> n.has("costUsd"))).isTrue();
    }

    @Test
    void agentResultsAreRecordedAsTracesAndLinkedToEvaluations() throws Exception {
        AgentResult result =
                AgentResult.builder()
                        .finalAnswer("done")
                        .addStep(new AgentResult.AgentStep("t", "lookup", "{}", "o"))
                        .completed(true)
                        .iterations(1)
                        .build();
        AgentAssertions.assertThat(result).completedSuccessfully().usesTool("lookup");
        List<JsonNode> evals = evals();
        String traceId = evals.get(0).path("traceId").asText();
        assertThat(traceId).startsWith("t_");
        List<String> traces = Files.readAllLines(root.resolve("runs/RUN-PROFILE-1/traces.jsonl"));
        assertThat(traces).hasSize(1);
        JsonNode t = RunWriter.MAPPER.readTree(traces.get(0));
        assertThat(t.path("traceId").asText()).isEqualTo(traceId);
        assertThat(t.path("steps").get(0).path("action").asText()).isEqualTo("lookup");
    }

    @Test
    void casesNotEvaluatedNowAreCarriedFromTheLatestEarlierRunAndLabelled() throws Exception {
        // run 1 (FULL): two cases judged
        System.setProperty(ExportConfig.PROFILE, "FULL");
        EvalRun.get().bindTest("com.acme.T", "a()");
        EvalRecorder.record("Correctness", 1.0, 0.7, "ok", "j");
        EvalRun.get().bindTest("com.acme.T", "b()");
        EvalRecorder.record("Correctness", 0.2, 0.7, "bad", "j");
        EvalRun.get().finish();
        // run 2 (BUILD): only a() is judged; b() was not evaluated
        EvalRun.resetForTests();
        System.setProperty(ExportConfig.RUN_ID, "RUN-PROFILE-2");
        System.setProperty(ExportConfig.PROFILE, "BUILD");
        EvalRecorder.reset();
        EvalRecorder.activate();
        EvalRun.get().bindTest("com.acme.T", "a()");
        EvalRecorder.record("Correctness", 1.0, 0.7, "ok", "j");
        EvalRun.get().finish();
        List<String> lines =
                Files.readAllLines(root.resolve("runs/RUN-PROFILE-2/evaluations.jsonl"));
        assertThat(lines).hasSize(2);
        JsonNode carried = RunWriter.MAPPER.readTree(lines.get(1));
        assertThat(carried.path("source").asText()).isEqualTo("CARRIED");
        assertThat(carried.path("evaluatedInRun").asText()).isEqualTo("RUN-PROFILE-1");
        assertThat(carried.path("passed").asBoolean()).isFalse();
        JsonNode run =
                RunWriter.MAPPER.readTree(root.resolve("runs/RUN-PROFILE-2/run.json").toFile());
        assertThat(run.path("summary").path("bySource").path("CARRIED").asInt()).isEqualTo(1);
        assertThat(run.path("summary").path("failed").asInt()).isEqualTo(1);
    }
}
