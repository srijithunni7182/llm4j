package io.github.llm4j.eval.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.report.EvalRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunBundleTest {

    @TempDir Path root;

    @BeforeEach
    void setUp() {
        EvalRun.resetForTests();
        System.setProperty(ExportConfig.DIR, root.toString());
        System.setProperty(ExportConfig.RUN_ID, "RUN-TEST-0001");
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

    private List<JsonNode> lines(String file) throws Exception {
        List<JsonNode> out = new java.util.ArrayList<>();
        for (String l : Files.readAllLines(root.resolve("runs/RUN-TEST-0001").resolve(file))) {
            out.add(RunWriter.MAPPER.readTree(l));
        }
        return out;
    }

    @Test
    void writesAnAppendOnlyBundleWithStableKeys() throws Exception {
        EvalScenario s = new EvalScenario("refund-inside-window", "Can I return?", null, null, null, null, null);
        EvalRun.get().bindScenario(s);
        EvalRecorder.record("Answer Correctness", 0.35, 0.7, "wrong window", "gemini-2.5-pro");
        EvalRecorder.record("Faithfulness", 0.9, 0.7, "ok", "gemini-2.5-pro");
        EvalRun.get().recordTest("com.acme.T", "refund()", "PASSED", 12, null);
        EvalRun.get().finish();

        List<JsonNode> evals = lines("evaluations.jsonl");
        assertThat(evals).hasSize(2);
        assertThat(evals.get(0).get("key").asText()).isEqualTo("k_e80a100a2103750c");
        assertThat(evals.get(0).get("metric").asText()).isEqualTo("answer-correctness");
        assertThat(evals.get(0).get("passed").asBoolean()).isFalse();
        assertThat(evals.get(1).get("key").asText()).isEqualTo("k_aba47f539957399a");
        assertThat(evals.get(1).get("seq").asInt()).isEqualTo(1);

        JsonNode run = RunWriter.MAPPER.readTree(root.resolve("runs/RUN-TEST-0001/run.json").toFile());
        assertThat(run.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(run.get("summary").get("passed").asInt()).isEqualTo(1);
        assertThat(run.get("summary").get("failed").asInt()).isEqualTo(1);
        assertThat(run.get("metrics")).hasSize(2);
        assertThat(Files.readAllLines(root.resolve("index.jsonl"))).hasSize(1);
        assertThat(lines("scenarios.jsonl")).hasSize(1);
        assertThat(lines("tests.jsonl")).hasSize(1);
    }

    @Test
    void repeatedMetricsOnOneCaseGetDistinctOccurrenceKeys() throws Exception {
        EvalRun.get().bindTest("com.acme.T", "cancel[2]");
        EvalRecorder.record("Faithfulness", 1, 0.7, null, null);
        EvalRecorder.record("Faithfulness", 1, 0.7, null, null);
        EvalRun.get().finish();
        List<JsonNode> evals = lines("evaluations.jsonl");
        assertThat(evals.get(0).get("key").asText()).isNotEqualTo(evals.get(1).get("key").asText());
    }

    @Test
    void checksRecordPassAndFailAndRethrowUnchanged() throws Exception {
        EvalRun.get().bindTest("com.acme.T", "tools()");
        MetricRef m = MetricRef.assertion("tool-order", "Tool order", "agents", "tools", "reasoning");
        EvalChecks.check(m, () -> {});
        AssertionError boom = new AssertionError("boom");
        assertThatThrownBy(() -> EvalChecks.check(m, () -> { throw boom; })).isSameAs(boom);
        EvalChecks.named("custom-id").dimension("safety").run(() -> EvalChecks.check(m, () -> {}));
        EvalRun.get().finish();
        List<JsonNode> evals = lines("evaluations.jsonl");
        assertThat(evals).hasSize(3);
        assertThat(evals.get(0).get("passed").asBoolean()).isTrue();
        assertThat(evals.get(1).get("passed").asBoolean()).isFalse();
        assertThat(evals.get(1).get("reason").asText()).isEqualTo("boom");
        assertThat(evals.get(2).get("metric").asText()).isEqualTo("custom-id");
    }

    @Test
    void disabledExportWritesNothing() {
        System.setProperty(ExportConfig.EXPORT, "false");
        try {
            EvalRecorder.record("M", 1, 0.5, null, null);
            EvalRun.get().finish();
            assertThat(Files.exists(root.resolve("runs"))).isFalse();
        } finally {
            System.clearProperty(ExportConfig.EXPORT);
        }
    }
}
