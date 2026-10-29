package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.eval.export.EvalRun;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F25: {@code weave eval} wrote an HTML page that kept no outputs and was not what the dashboard
 * reads. It now also writes the eval4j run bundle: every case with the input, the output that was
 * judged and each line's verdict, and a row per agent for the dashboard's By-agent table.
 */
class EvalWritesTheBundleTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path root;

    @BeforeEach
    void fresh() {
        EvalRun.resetForTests();
    }

    @AfterEach
    void clean() {
        EvalRun.resetForTests();
    }

    private static AgentFacts agent(String name, long cut, long fell) {
        return new AgentFacts(name, 1, 100, 200, new BigDecimal("0.001"), cut, fell);
    }

    private static ScenarioResult result(
            String target, String kind, String id, Status status, String output, String detail,
            List<String> tags, List<String> dims, AgentFacts... agents) {
        return new ScenarioResult(
                target, kind, "f.yaml", id, id, status,
                List.of(new ScenarioResult.Check("rubric", "Is whole", status, detail)),
                new BigDecimal("0.01"), null, "the question", output, tags, dims, List.of(agents));
    }

    private EvalReport.Run run() {
        List<ScenarioResult> results = new ArrayList<>();
        results.add(result("Writer", "agent", "w-1", Status.PASS, "a good article", "score 0.90: whole",
                List.of("split:validation"), List.of("completeness"), agent("Writer", 0, 0)));
        results.add(result("Editor", "agent", "e-1", Status.FAIL, "bad padding text", "score 0.40: padding",
                List.of(), List.of("style"), agent("Editor", 1, 0)));
        results.add(result("Main", "workflow", "m-1", Status.UNJUDGED, "the final text", "no judge ran",
                List.of(), List.of(), agent("Writer", 0, 0), agent("Checker", 0, 1)));
        return new EvalReport.Run("main.loom", false, results, 0, null, Map.of(),
                new EvalReport.JudgeFacts("claude-haiku", 3, 900, 90, new BigDecimal("0.004"), false),
                Map.of(), "weave eval main.loom");
    }

    private Path bundle() throws Exception {
        Path written = EvalBundleWriter.write(run(), root, "run-test-0001");
        assertThat(written).isDirectory();
        return written;
    }

    private static List<JsonNode> lines(Path file) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String l : Files.readAllLines(file)) if (!l.isBlank()) out.add(JSON.readTree(l));
        return out;
    }

    private static JsonNode evaluation(List<JsonNode> all, String scenario, String metricId) {
        return all.stream()
                .filter(e -> scenario.equals(e.path("scenarioId").asText()) && metricId.equals(e.path("metric").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + metricId + " for " + scenario + " in " + all));
    }

    @Test
    void theBundleIsWrittenWhereTheDashboardReadsAndIsComplete() throws Exception {
        Path bundle = bundle();

        assertThat(bundle.getParent().getFileName().toString()).isEqualTo("runs");
        assertThat(bundle.resolve("run.json")).exists();
        assertThat(JSON.readTree(bundle.resolve("run.json").toFile()).path("status").asText())
                .isEqualTo("COMPLETE");
        assertThat(bundle.resolve("scenarios.jsonl")).exists();
        assertThat(bundle.resolve("evaluations.jsonl")).exists();
    }

    @Test
    void everyCaseKeepsItsInputItsOutputAndEachLinesVerdictWithItsReason() throws Exception {
        List<JsonNode> evals = lines(bundle().resolve("evaluations.jsonl"));

        JsonNode writer = evaluation(evals, "w-1", "is-whole");
        assertThat(writer.path("input").asText()).isEqualTo("the question");
        assertThat(writer.path("actualOutput").asText()).isEqualTo("a good article");
        assertThat(writer.path("passed").asBoolean()).isTrue();
        assertThat(writer.path("reason").asText()).contains("whole");
        JsonNode editor = evaluation(evals, "e-1", "is-whole");
        assertThat(editor.path("passed").asBoolean()).isFalse();
        assertThat(editor.path("actualOutput").asText()).isEqualTo("bad padding text");
        assertThat(editor.path("reason").asText()).contains("padding");
    }

    @Test
    void anUnjudgedLineIsNotEvaluatedNeverPassed() throws Exception {
        List<JsonNode> evals = lines(bundle().resolve("evaluations.jsonl"));

        JsonNode unjudged = evaluation(evals, "m-1", "is-whole");

        assertThat(unjudged.path("status").asText()).isEqualTo("NOT_EVALUATED");
        assertThat(unjudged.path("actualOutput").asText()).isEqualTo("the final text");
    }

    @Test
    void everyAgentGetsARowEvenOneSeenOnlyInsideAWorkflow() throws Exception {
        Path bundle = bundle();
        List<JsonNode> scenarios = lines(bundle.resolve("scenarios.jsonl"));

        List<String> agentTags = new ArrayList<>();
        for (JsonNode s : scenarios)
            for (JsonNode t : s.path("tags")) if (t.asText().startsWith("agent:")) agentTags.add(t.asText());
        assertThat(agentTags).contains("agent:Writer", "agent:Editor", "agent:Checker");
    }

    @Test
    void aCutAnswerAndAFallbackAreRecordedAsChecksOfTheAgentThatHadThem() throws Exception {
        List<JsonNode> evals = lines(bundle().resolve("evaluations.jsonl"));

        assertThat(evaluation(evals, "e-1", "answer-whole").path("passed").asBoolean()).isFalse();
        assertThat(evaluation(evals, "m-1/Checker", "step-used-no-fallback").path("passed").asBoolean()).isFalse();
        assertThat(evaluation(evals, "w-1", "answer-whole").path("passed").asBoolean()).isTrue();
    }

    @Test
    void theJudgeIsDeclaredInTheRunHeader() throws Exception {
        JsonNode runJson = JSON.readTree(bundle().resolve("run.json").toFile());

        assertThat(runJson.toString()).contains("claude-haiku");
    }

    @Test
    void aSecondRunGetsItsOwnFolder() throws Exception {
        Path first = EvalBundleWriter.write(run(), root, "run-test-0001");
        EvalRun.resetForTests();
        Path second = EvalBundleWriter.write(run(), root, "run-test-0002");

        assertThat(first).isNotEqualTo(second);
        assertThat(first).isDirectory();
        assertThat(second).isDirectory();
    }
}
