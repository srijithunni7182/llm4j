package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code weave next --format json} reports the ten stages of the journey with a state each, read
 * from the files and the recorded results (never by asking a model), and names the primary next
 * step. The editor's Outline and the skills read this and nothing else.
 */
class JourneyStagesTest {
    @TempDir Path dir;

    private JsonNode next(Path project) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out, true));
        try {
            assertThat(WeaveCLI.commandLine().execute("next", project.toString(), "--format", "json")).isZero();
        } finally {
            System.setOut(old);
        }
        return new ObjectMapper().readTree(out.toString());
    }

    private Path project() {
        Path p = dir.resolve("p");
        assertThat(WeaveCLI.commandLine().execute("init", "pipeline", p.toString(), "--flat")).isZero();
        return p;
    }

    private static String state(JsonNode json, String id) {
        for (JsonNode s : json.path("stages")) if (s.path("id").asText().equals(id)) return s.path("state").asText();
        throw new AssertionError("no stage " + id + " in " + json);
    }

    private static void deleteTree(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }
    }

    @Test
    void aFreshStarterHasTenStagesInOrderAndTheScriptStageIsDone() throws Exception {
        JsonNode json = next(project());

        assertThat(json.path("stages")).hasSize(10);
        assertThat(json.path("stages").findValuesAsText("id"))
                .containsExactly(
                        "decide-agents", "design-evaluation", "golden-dataset", "script", "audit-and-review",
                        "scripted-tests", "preflight", "real-run-read", "real-evaluation", "deploy");
        assertThat(state(json, "decide-agents")).isEqualTo("done");
        assertThat(state(json, "script")).isEqualTo("done");
        assertThat(state(json, "preflight")).isEqualTo("not-started");
        assertThat(state(json, "deploy")).isEqualTo("not-started");
    }

    @Test
    void theEvaluationDesignIsDoneOnceItsFileExists() throws Exception {
        Path p = project();
        Files.writeString(p.resolve("EVAL_DESIGN.md"), "# Evaluation design\n");

        assertThat(state(next(p), "design-evaluation")).isEqualTo("done");
    }

    @Test
    void skippingEvaluationInTheReadmeShowsAsSkippedNotAsMissing() throws Exception {
        Path p = project();
        Files.writeString(p.resolve("README.md"), "Evaluation: skipped (a demo)\n");

        JsonNode json = next(p);
        assertThat(state(json, "design-evaluation")).isEqualTo("skipped");
        assertThat(state(json, "real-evaluation")).isEqualTo("skipped");
        assertThat(state(json, "golden-dataset")).as("the starter has a dataset, so it is not skipped").isEqualTo("done");
        Path bare = dir.resolve("bare");
        assertThat(WeaveCLI.commandLine().execute("init", "pipeline", bare.toString(), "--flat")).isZero();
        deleteTree(bare.resolve("eval"));
        Files.writeString(bare.resolve("README.md"), "Evaluation: skipped\n");
        assertThat(state(next(bare), "golden-dataset")).isEqualTo("skipped");
    }

    @Test
    void aScriptThatDoesNotCheckIsFailingWithTheReason() throws Exception {
        Path p = project();
        Files.writeString(p.resolve("main.loom"), "workflow Main( {");

        JsonNode json = next(p);
        assertThat(state(json, "script")).isEqualTo("failing");
        assertThat(json.path("stages").get(3).path("detail").asText()).isNotBlank();
        assertThat(json.path("primary").path("id").asText()).isEqualTo("script");
    }

    @Test
    void thePrimaryStepIsTheFirstStageThatIsNotDoneOrSkipped() throws Exception {
        Path p = project();
        Files.writeString(p.resolve("README.md"), "Evaluation: skipped\n");
        Files.writeString(p.resolve("EVAL_DESIGN.md"), "x");

        JsonNode json = next(p);
        String primary = json.path("primary").path("id").asText();
        boolean seen = false;
        for (JsonNode s : json.path("stages")) {
            if (s.path("id").asText().equals(primary)) seen = true;
            else if (!seen) assertThat(s.path("state").asText()).isIn("done", "skipped");
        }
        assertThat(seen).isTrue();
    }

    @Test
    void aStageThatFailsIsTheStepToTakeBeforeOneNotStarted() throws Exception {
        Path p = project();
        Files.writeString(p.resolve("main.loom"), "workflow Main( {");

        assertThat(next(p).path("primary").path("id").asText()).isEqualTo("script");
    }

    @Test
    void theRealRunIsReadOnlyAfterAPassingPreflightAndOnlyForThisScript() throws Exception {
        Path p = project();
        Files.writeString(p.resolve("EVAL_DESIGN.md"), "# Evaluation design\n");
        Files.createDirectories(p.resolve(".loom"));
        Files.writeString(p.resolve(".loom/preflight.json"),
                "{\"at\":\"2026-10-29T10:00:00Z\",\"scriptHash\":\"" + PreflightCommand.hash(Files.readString(p.resolve("main.loom")))
                        + "\",\"mock\":false,\"checks\":[{\"id\":\"C3\",\"result\":\"pass\"}]}");

        JsonNode before = next(p);
        assertThat(state(before, "preflight")).isEqualTo("done");
        assertThat(state(before, "real-run-read")).isEqualTo("not-started");
        assertThat(before.path("primary").path("id").asText()).isEqualTo("real-run-read");
        assertThat(before.path("primary").path("command").asText()).isEqualTo("weave next --confirm real-run");

        assertThat(WeaveCLI.commandLine().execute("next", p.toString(), "--confirm", "real-run")).isZero();
        assertThat(state(next(p), "real-run-read")).isEqualTo("done");
    }

    @Test
    void aRealEvaluationOfAnEarlierScriptIsStaleAndAMockOneDoesNotCount() throws Exception {
        Path p = project();
        String hash = PreflightCommand.hash(Files.readString(p.resolve("main.loom")));
        String dataset = io.github.llm4j.loom.eval.DatasetIdentity.of(p.resolve("eval/golden")).describe();
        EvalRecord.write(p, "mock", dataset, hash);
        assertThat(state(next(p), "real-evaluation")).isEqualTo("not-started");

        EvalRecord.write(p, "real", dataset, hash);
        assertThat(state(next(p), "real-evaluation")).isEqualTo("done");

        EvalRecord.write(p, "real", "version 0 (a different dataset)", hash);
        assertThat(state(next(p), "real-evaluation")).isEqualTo("stale");

        EvalRecord.write(p, "real", dataset, "an-older-script");
        assertThat(state(next(p), "real-evaluation")).isEqualTo("stale");
    }

    @Test
    void deployFilesMakeTheLastStageDone() throws Exception {
        Path p = project();
        Files.createDirectories(p.resolve("deploy"));
        Files.writeString(p.resolve("deploy/compose.yaml"), "services: {}\n");

        assertThat(state(next(p), "deploy")).isEqualTo("done");
    }

    @Test
    void draftedCasesNoPersonReviewedAreNamedOnTheDatasetStage() throws Exception {
        Path p = project();
        Files.writeString(p.resolve("eval/golden/extra.yaml"), "- {id: a-1, input: q, tags: [\"author:assistant\"]}\n");

        JsonNode stage = next(p).path("stages").get(2);
        assertThat(stage.path("state").asText()).isEqualTo("done");
        assertThat(stage.path("detail").asText()).contains("1 drafted case").contains("no person has reviewed");
    }

    @Test
    void aDatasetWithAProblemFailsTheDatasetStageWithThatProblem() throws Exception {
        Path p = project();
        Files.writeString(p.resolve("eval/golden/broken.yaml"), "- {id: b-1}\n");

        JsonNode stage = next(p).path("stages").get(2);
        assertThat(stage.path("state").asText()).isEqualTo("failing");
        assertThat(stage.path("detail").asText()).isNotBlank();
    }
}
