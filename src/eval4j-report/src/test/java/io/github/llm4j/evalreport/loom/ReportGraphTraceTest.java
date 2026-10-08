package io.github.llm4j.evalreport.loom;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.eval.export.EvalRun;
import io.github.llm4j.eval.export.ExportConfig;
import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.eval.report.EvalRecorder;
import io.github.llm4j.evalreport.SchemaContractTest;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.LoomLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** V4.4: a workflow that uses the newer statements exports a trace that validates, with each statement as its own kind. */
class ReportGraphTraceTest {

    private static final Path ALL_STATEMENTS = Path.of("../loom/ai-agent4j-loom/src/test/resources/graph/all_statements.loom");

    @TempDir Path root;

    @BeforeEach
    void setUp() {
        EvalRun.resetForTests();
        System.setProperty(ExportConfig.DIR, root.toString());
        System.setProperty(ExportConfig.RUN_ID, "RUN-GRAPH-0001");
        EvalRecorder.reset();
        EvalRecorder.activate();
        EvalRun.get().bindTest("com.acme.GraphTest", "everything");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ExportConfig.DIR);
        System.clearProperty(ExportConfig.RUN_ID);
        EvalRun.resetForTests();
        EvalRecorder.reset();
    }

    private static WorkflowDef everything() throws Exception {
        return new LoomLoader().load(ALL_STATEMENTS.toAbsolutePath().toString()).getWorkflows().stream()
                .filter(w -> w.getName().equals("Everything"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theGraphUsesTheNewKindsAndCarriesSettingsAndTheTraceValidates() throws Exception {
        WorkflowTrace trace = LoomTrace.create().workflow(everything()).finish(null);
        EvalRun.get().recordWorkflowTrace(trace);
        EvalRun.get().finish();

        Path dir = root.resolve("runs/RUN-GRAPH-0001");
        assertThat(SchemaContractTest.violations(dir)).isEmpty();
        JsonNode nodes = SchemaContractTest.MAPPER
                .readTree(Files.readAllLines(dir.resolve("traces.jsonl")).get(0))
                .path("workflow").path("graph").path("nodes");
        List<String> kinds = new java.util.ArrayList<>();
        nodes.forEach(n -> kinds.add(n.path("kind").asText()));
        assertThat(kinds).contains("foreach", "guardrail", "call", "rewind", "decide", "broadcast", "observe", "note")
                .doesNotContain("statement");
        JsonNode research = null;
        for (JsonNode n : nodes) {
            if ("delegate Researcher".equals(n.path("label").asText()) && n.has("attrs") && n.path("attrs").has("retry")) {
                research = n;
            }
        }
        assertThat(research).isNotNull();
        assertThat(research.path("attrs").path("retry").asInt()).isEqualTo(3);
        assertThat(research.path("attrs").path("timeoutMs").asInt()).isEqualTo(90_000);
    }

    @Test
    void aNodeWithNoSettingsHasNoAttrsKey() throws Exception {
        EvalRun.get().recordWorkflowTrace(LoomTrace.create().workflow(everything()).finish(null));
        EvalRun.get().finish();

        JsonNode nodes = SchemaContractTest.MAPPER
                .readTree(Files.readAllLines(root.resolve("runs/RUN-GRAPH-0001/traces.jsonl")).get(0))
                .path("workflow").path("graph").path("nodes");
        assertThat(nodes.get(0).path("kind").asText()).isEqualTo("start");
        assertThat(nodes.get(0).has("attrs")).isFalse();
    }
}
