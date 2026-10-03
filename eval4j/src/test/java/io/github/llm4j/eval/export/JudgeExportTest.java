package io.github.llm4j.eval.export;

import static io.github.llm4j.eval.judge.LlmJudgeCondition.llmJudged;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.judge.InMemoryJudgeCache;
import io.github.llm4j.eval.report.EvalRecorder;
import io.github.llm4j.eval.support.StubJudge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A real judged condition exports calls, cache reuse and judge statistics into the bundle. */
class JudgeExportTest {

    @TempDir Path root;

    @BeforeEach
    void setUp() {
        EvalRun.resetForTests();
        JudgeStats.reset();
        System.setProperty(ExportConfig.DIR, root.toString());
        System.setProperty(ExportConfig.RUN_ID, "RUN-JUDGE-0001");
        EvalRecorder.reset();
        EvalRecorder.activate();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ExportConfig.DIR);
        System.clearProperty(ExportConfig.RUN_ID);
        EvalRun.resetForTests();
        JudgeStats.reset();
        EvalRecorder.reset();
    }

    @Test
    void secondJudgementOfTheSameThingIsMarkedReusedAndStatsAreExported() throws Exception {
        StubJudge judge = StubJudge.rating(m -> 5);
        InMemoryJudgeCache cache = InMemoryJudgeCache.create();
        EvalRun.get().declareJudge("judge-main", "stub", "stub-1", 0.0, 1, "MEAN");
        EvalRun.get().declareAgent("agent", "p", "m", "prompt.md", "v1", List.of("t"));
        EvalRun.get()
                .declareDataset(
                        "ds",
                        "ds.yaml",
                        "ds.yaml",
                        List.of(new EvalScenario("s1", "q", null, null, null, null, null)));
        EvalRun.get().bindScenario(new EvalScenario("s1", "q", null, null, null, null, null));
        for (int i = 0; i < 2; i++) {
            llmJudged("Correctness")
                    .criteria("c")
                    .judge(judge)
                    .threshold(0.7)
                    .cache(cache)
                    .judgeIdentifier("judge-main")
                    .build()
                    .matches("an answer");
        }
        EvalRun.get().recordTrace(java.util.Map.of("traceId", "t1", "type", "AGENT_STEPS"));
        EvalRun.get().recordOptimization(java.util.Map.of("id", "o1"));
        EvalRun.get().finish();

        Path dir = root.resolve("runs/RUN-JUDGE-0001");
        List<String> lines = Files.readAllLines(dir.resolve("evaluations.jsonl"));
        assertThat(lines).hasSize(2);
        JsonNode first = RunWriter.MAPPER.readTree(lines.get(0));
        JsonNode second = RunWriter.MAPPER.readTree(lines.get(1));
        assertThat(first.path("source").asText()).isEqualTo("FRESH");
        assertThat(first.path("calls").asInt()).isEqualTo(1);
        assertThat(second.path("source").asText()).isEqualTo("REUSED");
        assertThat(second.path("calls").asInt()).isZero();
        assertThat(first.path("judgeId").asText()).isEqualTo("judge-main");

        JsonNode run = RunWriter.MAPPER.readTree(dir.resolve("run.json").toFile());
        JsonNode stats = run.path("env").path("judges").get(0).path("stats");
        assertThat(stats.path("calls").asInt()).isEqualTo(1);
        assertThat(stats.path("cacheHits").asInt()).isEqualTo(1);
        assertThat(run.path("env").path("datasets").get(0).path("scenarioCount").asInt())
                .isEqualTo(1);
        assertThat(run.path("env").path("agents").get(0).path("model").asText()).isEqualTo("m");
        assertThat(run.path("summary").path("bySource").path("REUSED").asInt()).isEqualTo(1);
        assertThat(Files.readAllLines(dir.resolve("traces.jsonl"))).hasSize(1);
        assertThat(Files.readAllLines(dir.resolve("optimizations.jsonl"))).hasSize(1);
        assertThat(Files.readAllLines(dir.resolve("scenarios.jsonl"))).hasSize(1);
    }

    @Test
    void drainClearsThisThreadsTotals() {
        JudgeTelemetry.callMade("j", 10, 5, 2, false);
        JudgeTelemetry.Usage u = JudgeTelemetry.drain();
        assertThat(u.calls()).isEqualTo(1);
        assertThat(u.servedFromCache()).isFalse();
        assertThat(JudgeTelemetry.drain().calls()).isZero();
        JudgeTelemetry.cacheHit("j");
        assertThat(JudgeTelemetry.drain().servedFromCache()).isTrue();
    }

    @Test
    void metricRefBuildersAndEnvironment() {
        MetricRef m =
                MetricRef.of("Custom")
                        .family("Prompts")
                        .facet("Compare")
                        .dimension("Safety")
                        .kind(Kind.PAIRWISE)
                        .threshold(0.5)
                        .budget(4.0, "s");
        assertThat(m.family()).isEqualTo("prompts");
        assertThat(m.dimension()).isEqualTo("safety");
        assertThat(m.budget()).isEqualTo(4.0);
        assertThat(
                        MetricRef.measured("l", "Latency", "agents", "answers", "efficiency", "s")
                                .unit())
                .isEqualTo("s");
        assertThat(RunEnvironment.runtime()).containsKeys("javaVersion", "os");
        assertThat(RunEnvironment.source()).isNotNull();
        assertThat(ExportConfig.newRunId()).startsWith("R");
        assertThat(ExportConfig.fromSystem().profile()).isEqualTo("BUILD");
    }
}
