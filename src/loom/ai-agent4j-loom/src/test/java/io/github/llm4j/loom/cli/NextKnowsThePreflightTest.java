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
 * {@code weave next} is the roadmap: it suggests the preflight once, treats a result as stale when
 * the script changed, and gives the same advice as JSON for the skills and the editor.
 */
class NextKnowsThePreflightTest {
    @TempDir Path dir;

    private record Out(int code, String out) {}

    private Out next(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out, true));
        try {
            String[] all = new String[args.length + 1];
            all[0] = "next";
            System.arraycopy(args, 0, all, 1, args.length);
            return new Out(WeaveCLI.commandLine().execute(all), out.toString());
        } finally {
            System.setOut(old);
        }
    }

    private Path project() {
        Path p = dir.resolve("p");
        assertThat(WeaveCLI.commandLine().execute("init", "pipeline", p.toString(), "--flat")).isZero();
        return p;
    }

    private void record(Path project, String hashOf, boolean mock, String result) throws Exception {
        String hash = PreflightCommand.hash(Files.readString(project.resolve("main.loom"))) + hashOf;
        Files.createDirectories(project.resolve(".loom"));
        Files.writeString(project.resolve(".loom/preflight.json"),
                "{\"at\":\"2026-10-29T10:00:00Z\",\"script\":\"main.loom\",\"scriptHash\":\"" + hash
                        + "\",\"mock\":" + mock + ",\"checks\":[{\"id\":\"C3\",\"agent\":\"Writer\",\"result\":\"" + result
                        + "\",\"detail\":\"the answer was cut at the output limit\"}]}");
    }

    @Test
    void aProjectThatNeverRanItIsSuggestedTheRealPreflightOnce() {
        String text = next(project().toString()).out();

        assertThat(text).contains("Try one real answer").contains("$ weave preflight main.loom");
        assertThat(text.split("Try one real answer", -1).length - 1).isEqualTo(1);
    }

    @Test
    void aPassingCurrentRealPreflightIsNotSuggestedAgain() throws Exception {
        Path p = project();
        record(p, "", false, "pass");

        assertThat(next(p.toString()).out()).doesNotContain("Try one real answer").doesNotContain("Fix what the preflight found");
    }

    @Test
    void aMockPreflightDoesNotCountBecauseItTriedNothingReal() throws Exception {
        Path p = project();
        record(p, "", true, "pass");

        assertThat(next(p.toString()).out()).contains("Try one real answer");
    }

    @Test
    void aChangedScriptMakesTheResultStaleAndSaysSo() throws Exception {
        Path p = project();
        record(p, "x", false, "pass");

        String text = next(p.toString()).out();

        assertThat(text).contains("script changed since the last preflight");
    }

    @Test
    void aFailingPreflightNamesWhatFailed() throws Exception {
        Path p = project();
        record(p, "", false, "fail");

        assertThat(next(p.toString()).out()).contains("Fix what the preflight found").contains("cut at the output limit");
    }

    @Test
    void jsonGivesTheStepsAndThePreflightState() throws Exception {
        Path p = project();
        record(p, "", false, "pass");

        Out o = next(p.toString(), "--format", "json");

        JsonNode json = new ObjectMapper().readTree(o.out());
        assertThat(json.path("preflight").path("state").asText()).isEqualTo("done");
        assertThat(json.path("steps").isArray()).isTrue();
        assertThat(json.path("steps").get(0).path("what").asText()).isNotBlank();
        assertThat(json.path("steps").get(0).has("command")).isTrue();
    }

    @Test
    void aBadFormatIsRefused() {
        assertThat(next(project().toString(), "--format", "xml").code()).isEqualTo(2);
    }

    @Test
    void afterAPassingPreflightTheNextStepIsToReadTheRealAnswer() throws Exception {
        Path p = project();
        record(p, "", false, "pass");

        assertThat(next(p.toString()).out())
                .contains("Read one real answer")
                .contains("$ weave next --confirm real-run");
    }

    @Test
    void confirmingTheReadRemovesTheStepAndIsRemembered() throws Exception {
        Path p = project();
        record(p, "", false, "pass");

        Out confirmed = next(p.toString(), "--confirm", "real-run");

        assertThat(confirmed.code()).isZero();
        assertThat(Files.readString(p.resolve(".loom/confirmed.json"))).contains("real-run");
        assertThat(next(p.toString()).out()).doesNotContain("Read one real answer");
    }

    @Test
    void aChangedScriptNeedsTheAnswerReadAgain() throws Exception {
        Path p = project();
        record(p, "", false, "pass");
        next(p.toString(), "--confirm", "real-run");
        Files.writeString(p.resolve("main.loom"), Files.readString(p.resolve("main.loom")) + "\n// edited\n");
        record(p, "", false, "pass");

        assertThat(next(p.toString()).out()).contains("Read one real answer");
    }

    @Test
    void onlyKnownStepsCanBeConfirmed() {
        Out refused = next(project().toString(), "--confirm", "everything");

        assertThat(refused.code()).isEqualTo(2);
    }

    @Test
    void jsonSaysWhetherTheRealAnswerWasRead() throws Exception {
        Path p = project();
        record(p, "", false, "pass");
        assertThat(new ObjectMapper().readTree(next(p.toString(), "--format", "json").out())
                        .path("realRun").path("confirmed").asBoolean(true)).isFalse();

        next(p.toString(), "--confirm", "real-run");

        assertThat(new ObjectMapper().readTree(next(p.toString(), "--format", "json").out())
                        .path("realRun").path("confirmed").asBoolean(false)).isTrue();
    }
}
