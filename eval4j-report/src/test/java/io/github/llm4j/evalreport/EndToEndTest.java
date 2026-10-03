package io.github.llm4j.evalreport;

import static io.github.llm4j.eval.judge.LlmJudgeCondition.llmJudged;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.eval.assertions.AgentAssertions;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.export.EvalRun;
import io.github.llm4j.eval.export.ExportConfig;
import io.github.llm4j.eval.export.JudgeStats;
import io.github.llm4j.eval.judge.InMemoryJudgeCache;
import io.github.llm4j.eval.report.EvalRecorder;
import io.github.llm4j.evalreport.config.ReportConfig;
import io.github.llm4j.evalreport.format.RunStore;
import io.github.llm4j.evalreport.model.ReportModel;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** eval4j exports a run; eval4j-report reads it: the two halves of the contract meet here. */
class EndToEndTest {

    /**
     * Exports a small real run (judged, asserted, with a trace) and returns the bundle directory.
     */
    static Path exportSampleRun(Path root) {
        EvalRun.resetForTests();
        JudgeStats.reset();
        System.setProperty(ExportConfig.DIR, root.toString());
        System.setProperty(ExportConfig.RUN_ID, "RUN-E2E-0001");
        try {
            EvalRecorder.reset();
            EvalRecorder.activate();
            EvalScenario sc =
                    new EvalScenario(
                            "refund",
                            "Can I return it?",
                            null,
                            "Within 14 days",
                            List.of("lookup"),
                            null,
                            null,
                            "refund",
                            List.of("correctness", "reasoning"),
                            List.of("demo"));
            EvalRun.get().declareDataset("ds", "ds.yaml", "ds.yaml", List.of(sc));
            EvalRun.get().declareJudge("judge-main", "stub", "stub-1", 0.0, 1, "MEAN");
            EvalRun.get().bindTest("com.acme.AgentTest", "refund");
            EvalRun.get().bindScenario(sc);
            io.github.llm4j.LLMClient judge =
                    new io.github.llm4j.LLMClient() {
                        @Override
                        public LLMResponse chat(LLMRequest r) {
                            return LLMResponse.builder()
                                    .content("{\"reasoning\":\"fine\",\"rating\":4}")
                                    .build();
                        }

                        @Override
                        public java.util.stream.Stream<LLMResponse> chatStream(LLMRequest r) {
                            return java.util.stream.Stream.of(chat(r));
                        }
                    };
            llmJudged("Correctness")
                    .criteria("c")
                    .judge(judge)
                    .threshold(0.7)
                    .cache(InMemoryJudgeCache.create())
                    .judgeIdentifier("judge-main")
                    .build()
                    .matches("Within 14 days");
            AgentResult result =
                    AgentResult.builder()
                            .finalAnswer("Within 14 days")
                            .completed(true)
                            .iterations(1)
                            .addStep(new AgentResult.AgentStep("t", "lookup", "{}", "ok"))
                            .build();
            AgentAssertions.assertThat(result).usesTool("lookup").completedSuccessfully();
            EvalRun.get().recordTest("com.acme.AgentTest", "refund", "PASSED", 5, null);
            EvalRun.get().unbind();
            EvalRun.get().finish();
            return root.resolve("runs/RUN-E2E-0001");
        } finally {
            System.clearProperty(ExportConfig.DIR);
            System.clearProperty(ExportConfig.RUN_ID);
            EvalRecorder.reset();
        }
    }

    @Test
    void exportedRunProducesTheExpectedReport(@TempDir Path root) {
        exportSampleRun(root);
        ReportModel m =
                EvalReport.build(new RunStore(root), null, null, true, ReportConfig.defaults());
        assertThat(m.overall().passed()).isEqualTo(3);
        assertThat(m.overall().failed()).isZero();
        assertThat(m.dimensions()).extracting(d -> d.id()).contains("correctness", "reasoning");
        var correctness =
                m.dimensions().stream()
                        .filter(d -> d.id().equals("correctness"))
                        .findFirst()
                        .orElseThrow();
        assertThat(correctness.coverage().state()).isEqualTo("COVERED");
        assertThat(m.cases()).hasSize(1);
        assertThat(m.cases().get(0).name()).isEqualTo("refund");
        assertThat(m.traces()).hasSize(1);
        assertThat(m.compare()).as("only one run, so no baseline").isNull();
        assertThat(m.meta().env().path("judges").get(0).path("stats").path("calls").asInt())
                .isEqualTo(1);
    }
}
