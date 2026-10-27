package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.generate.FileBlocks;
import io.github.llm4j.loom.generate.GenerateFixtures;
import io.github.llm4j.loom.generate.PlanJson;
import io.github.llm4j.loom.generate.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R33: what an editor reads while a generation runs: one JSON object per line, and a last one with
 * the result.
 */
class GenerateEventsTest {

    @TempDir Path dir;

    private static List<JsonNode> lines(String out) throws Exception {
        List<JsonNode> list = new ArrayList<>();
        for (String l : out.split("\n")) if (!l.isBlank()) list.add(new ObjectMapper().readTree(l));
        return list;
    }

    @Test
    void aBuildTellsItsStagesItsAttemptsAndEachGateThenTheResultWithTheCardText() throws Exception {
        String files = "ok\n" + FileBlocks.render(GenerateFixtures.files());
        var o =
                GenerateCommandTest.generate(
                        GenerateCommandTest.sequence(
                                "[]", PlanJson.write(GenerateFixtures.plan()), files),
                        "x",
                        "--model",
                        "m",
                        "--out",
                        dir.resolve("built").toString(),
                        "--flat",
                        "--format",
                        "json");
        assertThat(o.code()).as(o.out() + o.err()).isZero();
        List<JsonNode> events = lines(o.out());
        assertThat(events.stream().map(e -> e.get("event").asText()))
                .startsWith("stage", "stage", "plan", "stage", "attempt")
                .endsWith("result");
        assertThat(
                        events.stream()
                                .filter(e -> e.get("event").asText().equals("stage"))
                                .map(e -> e.get("stage").asText()))
                .containsExactly("clarify", "plan", "write");
        var gates = events.stream().filter(e -> e.get("event").asText().equals("gate")).toList();
        assertThat(gates)
                .extracting(e -> e.get("gate").asText())
                .containsExactly("G1", "G2", "G3", "G4", "G5");
        assertThat(gates).allMatch(e -> e.get("passed").asBoolean());
        JsonNode result = events.get(events.size() - 1);
        assertThat(result.get("exit").asInt()).isZero();
        assertThat(result.get("text").asText()).contains("✓ Made SupportNotify");
        // nothing but JSON lines on standard output
        assertThat(o.out().lines().allMatch(l -> l.startsWith("{") && l.endsWith("}"))).isTrue();
    }

    @Test
    void aFailureIsAResultWithItsExitCodeAndTheFailedGates() throws Exception {
        var o =
                GenerateCommandTest.generate(
                        GenerateCommandTest.sequence(
                                "[]", PlanJson.write(GenerateFixtures.plan()), "no files"),
                        "x",
                        "--model",
                        "m",
                        "--out",
                        dir.resolve("nope").toString(),
                        "--flat",
                        "--max-attempts",
                        "1",
                        "--format",
                        "json");
        assertThat(o.code()).isEqualTo(3);
        List<JsonNode> events = lines(o.out());
        JsonNode result = events.get(events.size() - 1);
        assertThat(result.get("exit").asInt()).isEqualTo(3);
        assertThat(result.get("text").asText()).contains("The gates did not pass");
        assertThat(
                        events.stream()
                                .filter(e -> e.get("event").asText().equals("gate"))
                                .anyMatch(e -> !e.get("passed").asBoolean()))
                .isTrue();
    }

    @Test
    void thePlanOnlyAndEditModesTellToo() throws Exception {
        var plan =
                GenerateCommandTest.generate(
                        GenerateCommandTest.sequence("[]", PlanJson.write(GenerateFixtures.plan())),
                        "x",
                        "--model",
                        "m",
                        "--plan-only",
                        "--out",
                        dir.resolve("p").toString(),
                        "--format",
                        "json");
        List<JsonNode> pe = lines(plan.out());
        assertThat(pe.stream().map(e -> e.get("event").asText()))
                .contains("plan")
                .endsWith("result");
        Path project = Files.createDirectory(dir.resolve("proj"));
        try (var ws =
                Writer.render(
                        GenerateFixtures.files(),
                        GenerateFixtures.plan(),
                        Writer.Layout.FLAT,
                        new Writer.Stamp("t", "d"),
                        dir)) {
            ws.commit(project, false);
        }
        String patch =
                "=== edit: main.loom ===\n<<<<<<< SEARCH\ntemperature: 0.3\n=======\ntemperature: 0.1\n>>>>>>> REPLACE\n=== end ===\n";
        var edit =
                GenerateCommandTest.generate(
                        GenerateCommandTest.sequence(patch),
                        "calmer",
                        "--edit",
                        project.resolve("main.loom").toString(),
                        "--model",
                        "m",
                        "--format",
                        "json");
        List<JsonNode> ee = lines(edit.out());
        assertThat(ee.stream().map(e -> e.get("event").asText()))
                .contains("stage", "attempt", "gate")
                .endsWith("result");
        assertThat(ee.get(ee.size() - 1).get("text").asText()).contains("temperature: 0.1");
        assertThat(
                        ee.stream()
                                .filter(e -> e.get("event").asText().equals("gate"))
                                .map(e -> e.get("gate").asText()))
                .contains("G1", "G2", "G3", "G5");
    }
}
