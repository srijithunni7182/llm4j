package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.wtest.TestRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R1 to R4, R6 to R8, R10, R11 of spec 14: a run becomes a test case that passes and is safe. */
class CaptureTest {

    @TempDir Path dir;

    private Path script() throws Exception {
        return Runs.project(dir.resolve("proj"), Runs.TRIAGE);
    }

    private RunRecord order(String message) throws Exception {
        Path s = script();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var body = mapper.createObjectNode();
        body.putObject("input").put("message", message);
        body.putArray("humans").add("yes");
        var models = body.putObject("models");
        models.putObject("Intent").put("kind", "order_status").put("urgent", "no");
        models.put("Replier", "Your parcel shipped yesterday.");
        Path run = Runs.run(dir.resolve("store"), "r1", s, body, Map.of("message", message));
        return StoreReader.open(run);
    }

    @Test
    void aRunBecomesAValidCaseThatPassesAgainstTheScriptThatRanIt() throws Exception {
        RunRecord run = order("where is my parcel?");
        Capture.Captured c = Capture.capture(run, Capture.Options.defaults(), true);
        assertThat(c.written()).isTrue();
        // R1: a valid test file, and it passes when run
        var suite = TestRunner.load(run.script(), List.of(c.file()));
        assertThat(suite.problems()).isEmpty();
        var results = TestRunner.run(suite, null, false, false);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).failures()).isEmpty();
        assertThat(results.get(0).passed()).isTrue();
    }

    @Test
    void whatTheCollaboratorsSaidIsWhatTheCaseScriptsAndNothingIsInvented() throws Exception {
        Capture.Captured c =
                Capture.capture(order("where is my parcel?"), Capture.Options.defaults(), false);
        assertThat(c.text())
                .contains("kind: order_status")
                .contains("Your parcel shipped yesterday.")
                .contains("humans:")
                .contains("yes") // the person's answer
                .contains("status: observed")
                .contains("from: run r1")
                .contains("final: completed")
                .contains("classify Intent")
                .contains("delegate Replier");
        assertThat(c.written()).isFalse();
    }

    @Test
    void expectNoneLeavesOnlyHowTheRunEnded() throws Exception {
        Capture.Captured c =
                Capture.capture(
                        order("where is my parcel?"),
                        new Capture.Options(
                                null, Capture.Expect.NONE, null, null, null, true, null, null),
                        false);
        assertThat(c.text())
                .contains("final: completed")
                .doesNotContain("route:")
                .doesNotContain("result:");
    }

    @Test
    void personalDataAndSecretsNeverReachTheFileAndTheCaseStillPasses() throws Exception {
        String message = Hostile.message();
        RunRecord run = order(message);
        Capture.Captured c =
                Capture.capture(
                        run,
                        new Capture.Options(
                                null,
                                Capture.Expect.OBSERVED,
                                java.util.Set.copyOf(Hostile.TERMS),
                                null,
                                null,
                                true,
                                null,
                                null),
                        true);
        String onDisk = Files.readString(c.file());
        for (String v : Hostile.mustNotSurvive()) assertThat(onDisk).as(v).doesNotContain(v);
        var suite = TestRunner.load(run.script(), List.of(c.file()));
        assertThat(TestRunner.run(suite, null, false, false).get(0).passed()).isTrue();
        assertThat(c.masked()).contains("email", "secret");
    }

    @Test
    void aScriptThatChangedSinceTheRunIsRefusedNamingWhy() throws Exception {
        RunRecord run = order("where is my parcel?");
        Files.writeString(run.script(), Files.readString(run.script()) + "\n// changed\n");
        assertThatThrownBy(() -> Capture.capture(run, Capture.Options.defaults(), true))
                .hasMessageContaining("ran a different version");
    }

    @Test
    void maskingThatWouldChangeTheRouteFailsNamingTheField() throws Exception {
        // the script branches on the shape of the input: an email goes one way, text another
        String script =
                """
                agent A { model: "gemini-2.5-flash"  system: "x" }
                workflow Main(contact) {
                    alt (contact == "bob@acme.example") {
                        delegate "to bob" to A -> r
                        return r
                    } else {
                        note "somebody else"
                        return "other"
                    }
                }
                """;
        Path s = Runs.project(dir.resolve("p2"), script);
        Path run =
                Runs.run(
                        dir.resolve("store2"),
                        "r2",
                        s,
                        "input: { contact: 'bob@acme.example' }\nmodels:\n  A: 'hi bob'\n",
                        Map.of("contact", "bob@acme.example"));
        RunRecord record = StoreReader.open(run);
        assertThatThrownBy(() -> Capture.capture(record, Capture.Options.defaults(), true))
                .hasMessageContaining("masking changes the way")
                .hasMessageContaining("input contact")
                .hasMessageContaining("--mask-keep contact");
        // the developer may keep the field, taking the responsibility
        Capture.Captured kept =
                Capture.capture(
                        record,
                        new Capture.Options(
                                null,
                                Capture.Expect.OBSERVED,
                                null,
                                java.util.Set.of("contact"),
                                null,
                                true,
                                null,
                                null),
                        false);
        assertThat(kept.text()).contains("bob@acme.example");
    }

    @Test
    void capturingTheSameRunAgainChangesNothingAndAHandEditedFileIsNeverOverwritten()
            throws Exception {
        RunRecord run = order("where is my parcel?");
        Capture.Captured first = Capture.capture(run, Capture.Options.defaults(), true);
        Capture.Captured again = Capture.capture(run, Capture.Options.defaults(), true);
        assertThat(again.file()).isEqualTo(first.file());
        assertThat(again.unchanged()).isTrue();
        assertThat(again.written()).isFalse();
        Files.writeString(first.file(), Files.readString(first.file()) + "# my note\n");
        Capture.Captured third = Capture.capture(run, Capture.Options.defaults(), true);
        assertThat(third.conflict()).isTrue();
        assertThat(third.written()).isFalse();
        assertThat(Files.readString(first.file())).endsWith("# my note\n");
    }

    @Test
    void aRunThatWaitedForAPersonIsCapturedUpToWhereItStopped() throws Exception {
        Path s = script();
        Path run =
                Runs.run(
                        dir.resolve("store3"),
                        "r3",
                        s,
                        "input: { message: 'where is my parcel?' }\nmodels:\n  Intent: { kind: order_status, urgent: 'no' }\n  Replier: 'On its way.'\n",
                        Map.of("message", "where is my parcel?"));
        RunRecord record = StoreReader.open(run);
        Capture.Captured c = Capture.capture(record, Capture.Options.defaults(), true);
        assertThat(c.text()).contains("final: suspended");
        var suite = TestRunner.load(record.script(), List.of(c.file()));
        assertThat(TestRunner.run(suite, null, false, false).get(0).passed()).isTrue();
    }
}
