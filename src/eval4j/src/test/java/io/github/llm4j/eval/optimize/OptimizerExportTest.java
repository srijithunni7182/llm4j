package io.github.llm4j.eval.optimize;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.eval.export.EvalRun;
import io.github.llm4j.eval.export.ExportConfig;
import io.github.llm4j.eval.report.EvalRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A real optimizer run lands in the bundle as an optimization line the report can draw. */
class OptimizerExportTest {

    @TempDir Path root;

    @BeforeEach
    void setUp() {
        EvalRun.resetForTests();
        System.setProperty(ExportConfig.DIR, root.toString());
        System.setProperty(ExportConfig.RUN_ID, "RUN-OPT-0001");
        EvalRecorder.reset();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ExportConfig.DIR);
        System.clearProperty(ExportConfig.RUN_ID);
        EvalRun.resetForTests();
    }

    @Test
    void optimizerRunIsExported() throws Exception {
        OptimizationResult result =
                SimulationSupport.optimizer(SimulationSupport.lessonRewriter()).build().run();
        EvalRun.get().finish();
        List<String> lines =
                Files.readAllLines(root.resolve("runs/RUN-OPT-0001/optimizations.jsonl"));
        assertThat(lines).hasSize(1);
        JsonNode o = new com.fasterxml.jackson.databind.ObjectMapper().readTree(lines.get(0));
        assertThat(o.path("id").asText()).startsWith("opt-");
        assertThat(o.path("promptId").asText()).isEqualTo(SimulationSupport.PARAM);
        assertThat(o.path("rounds").get(0).path("action").asText()).isEqualTo("BASELINE");
        assertThat(o.path("rounds")).hasSize(result.trace().size() + 1);
        double best =
                o.path("rounds").get(o.path("rounds").size() - 1).path("bestScore").asDouble();
        assertThat(best).isGreaterThan(o.path("rounds").get(0).path("bestScore").asDouble());
        assertThat(o.path("diff").toString()).contains("ADD");
        assertThat(o.path("overfit").path("gap").isNumber()).isTrue();
    }
}
