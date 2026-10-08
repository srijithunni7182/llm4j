package io.github.llm4j.eval.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.eval.report.EvalDetails;
import io.github.llm4j.eval.report.EvalRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One verdict can be filed under several quality dimensions by recording it under explicit metrics.
 */
class RecordUnderMetricTest {

    @TempDir Path root;

    @BeforeEach
    void setUp() {
        EvalRun.resetForTests();
        System.setProperty(ExportConfig.DIR, root.toString());
        System.setProperty(ExportConfig.RUN_ID, "RUN-METRIC-0001");
        EvalRecorder.reset();
        EvalRecorder.activate();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ExportConfig.DIR);
        System.clearProperty(ExportConfig.RUN_ID);
        EvalRun.resetForTests();
        EvalRecorder.reset();
    }

    @Test
    void sameVerdictIsRecordedPerDimensionAsAJudgeMetric() throws Exception {
        EvalRun.get().bindTest("com.acme.T", "t");
        for (String dim : List.of("fact-checking", "grounding")) {
            MetricRef m =
                    new MetricRef(
                            "rubric-" + dim,
                            "Rubric adherence",
                            Kind.JUDGE,
                            "agents",
                            "answers",
                            dim,
                            null,
                            null,
                            null);
            EvalRecorder.record(m, 0.8, 0.7, "fine", "judge-main", EvalDetails.NONE);
        }
        EvalRun.get().unbind();
        EvalRun.get().finish();

        Path dir = root.resolve("runs/RUN-METRIC-0001");
        List<String> lines = Files.readAllLines(dir.resolve("evaluations.jsonl"));
        assertThat(lines).hasSize(2);
        JsonNode a = RunWriter.MAPPER.readTree(lines.get(0));
        JsonNode b = RunWriter.MAPPER.readTree(lines.get(1));
        assertThat(a.path("metric").asText()).isEqualTo("rubric-fact-checking");
        assertThat(b.path("metric").asText()).isEqualTo("rubric-grounding");
        JsonNode run = RunWriter.MAPPER.readTree(dir.resolve("run.json").toFile());
        assertThat(run.path("metrics").toString())
                .contains("\"dimension\":\"grounding\"")
                .contains("JUDGE");
    }
}
