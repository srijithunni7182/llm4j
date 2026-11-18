package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 37a: the journey the Guide reads. Every stage carries what it is for, what to look at,
 * the person's decision and what a skip loses, with the evidence its state came from; a skip is
 * recorded with its reason and survives until it is removed; the harness design is a stage of
 * every workflow.
 */
class GuideJourneyTest {
    @TempDir Path dir;

    private record Ran(int code, String out, String err) {}

    private static Ran weave(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            int code = WeaveCLI.commandLine().execute(args);
            return new Ran(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private Path project() {
        Path p = dir.resolve("p");
        assertThat(WeaveCLI.commandLine().execute("init", "pipeline", p.toString(), "--flat")).isZero();
        return p;
    }

    private static JsonNode next(Path p) throws Exception {
        Ran r = weave("next", p.toString(), "--format", "json");
        assertThat(r.code()).as(r.err()).isZero();
        return new ObjectMapper().readTree(r.out());
    }

    private static JsonNode stage(JsonNode json, String id) {
        for (JsonNode s : json.path("stages")) if (s.path("id").asText().equals(id)) return s;
        throw new AssertionError("no stage " + id + " in " + json);
    }

    @Test
    void everyStageCarriesItsFourTextsAndItsEvidence() throws Exception {
        JsonNode json = next(project());

        for (JsonNode s : json.path("stages")) {
            for (String field : new String[] {"why", "see", "decision", "missing"})
                assertThat(s.path(field).asText()).as(s.path("id").asText() + "." + field).isNotBlank();
            assertThat(s.path("evidence").isArray()).as(s.path("id").asText()).isTrue();
            assertThat(s.path("evidence")).as(s.path("id").asText()).isNotEmpty();
        }
    }

    @Test
    void theHarnessDesignIsTheSecondStageOfEveryWorkflowAndIsDoneByItsFile() throws Exception {
        Path p = dir.resolve("e");
        assertThat(WeaveCLI.commandLine().execute("init", "empty", p.toString(), "--flat")).isZero();
        JsonNode before = next(p);
        assertThat(before.path("stages")).hasSize(11);
        assertThat(before.path("stages").get(1).path("id").asText()).isEqualTo("design-harness");
        assertThat(stage(before, "design-harness").path("state").asText()).isEqualTo("not-started");
        assertThat(stage(before, "design-harness").path("detail").asText()).contains("short");

        Files.writeString(p.resolve("HARNESS.md"), "# Harness\n");

        assertThat(stage(next(p), "design-harness").path("state").asText()).isEqualTo("done");
    }

    @Test
    void aWorkflowThatRunsOnAScheduleIsToldItNeedsAFullHarnessDesign() throws Exception {
        Path p = dir.resolve("e");
        assertThat(WeaveCLI.commandLine().execute("init", "empty", p.toString(), "--flat")).isZero();
        Path script = p.resolve("main.loom");
        Files.writeString(
                script,
                Files.readString(script) + "\nschedule Nightly { cron: \"0 2 * * *\"  run: Main(request = \"news\") }\n");

        JsonNode s = stage(next(p), "design-harness");

        assertThat(s.path("detail").asText()).contains("full").contains("schedule");
    }

    @Test
    void aSkipIsRecordedWithItsReasonAndWhatItLosesAndCanBeRemoved() throws Exception {
        Path p = project();

        Ran skip = weave("next", p.toString(), "--skip", "deploy", "--because", "a demo for Friday");
        assertThat(skip.code()).as(skip.err()).isZero();
        assertThat(skip.out()).contains("Skipped").contains("deploy");

        JsonNode s = stage(next(p), "deploy");
        assertThat(s.path("state").asText()).isEqualTo("skipped");
        assertThat(s.path("skip").path("because").asText()).isEqualTo("a demo for Friday");
        assertThat(s.path("skip").path("missing").asText()).isEqualTo(s.path("missing").asText());
        assertThat(s.path("skip").path("at").asText()).isNotBlank();
        assertThat(Files.isRegularFile(p.resolve(".loom").resolve("skipped.json"))).isTrue();

        assertThat(weave("next", p.toString(), "--unskip", "deploy").code()).isZero();
        assertThat(stage(next(p), "deploy").path("state").asText()).isEqualTo("not-started");
    }

    @Test
    void aSkipNeverHidesAFailingOrAFinishedStage() throws Exception {
        Path p = project();
        assertThat(weave("next", p.toString(), "--skip", "script", "--because", "x").code()).isZero();

        assertThat(stage(next(p), "script").path("state").asText()).isEqualTo("done");
    }

    @Test
    void aSkipNeedsAReasonAndAStageThatExists() {
        Path p = project();

        Ran noReason = weave("next", p.toString(), "--skip", "scripted-tests");
        assertThat(noReason.code()).isEqualTo(2);
        assertThat(noReason.err()).contains("--because");

        Ran unknown = weave("next", p.toString(), "--skip", "tests", "--because", "x");
        assertThat(unknown.code()).isEqualTo(2);
        assertThat(unknown.err()).contains("scripted-tests").contains("design-harness");
    }

    @Test
    void skippingTheThreeEvaluationStagesAlsoWritesTheReadmeLine() throws Exception {
        Path p = project();
        for (String s : new String[] {"design-evaluation", "golden-dataset", "real-evaluation"})
            assertThat(weave("next", p.toString(), "--skip", s, "--because", "a demo").code()).isZero();

        assertThat(Files.readString(p.resolve("README.md"))).contains("Evaluation: skipped");
    }

    @Test
    void aSkipListThatCannotBeReadSkipsNothingAndSaysSo() throws Exception {
        Path p = project();
        Files.createDirectories(p.resolve(".loom"));
        Files.writeString(p.resolve(".loom").resolve("skipped.json"), "{ not json");

        JsonNode json = next(p);

        assertThat(stage(json, "deploy").path("state").asText()).isEqualTo("not-started");
        assertThat(json.path("warnings").toString()).contains("skipped.json");
    }

    @Test
    void theTextOutputSaysWhatTheSkipsLose() throws Exception {
        Path p = project();
        assertThat(weave("next", p.toString(), "--skip", "deploy", "--because", "a demo").code()).isZero();

        Ran text = weave("next", p.toString());

        assertThat(text.out()).contains("Skipped, by your choice").contains("deploy: a demo").contains("loses:");
    }
}
