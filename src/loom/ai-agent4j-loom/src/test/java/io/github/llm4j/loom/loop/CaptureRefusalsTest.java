package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R5, R12, R13 of spec 14: what capture refuses to do, and says why. */
class CaptureRefusalsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;

    private Path run(String message) throws Exception {
        Path s = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        ObjectNode body = JSON.createObjectNode();
        body.putObject("input").put("message", message);
        body.putArray("humans").add("yes");
        var models = body.putObject("models");
        models.putObject("Intent").put("kind", "order_status").put("urgent", "no");
        models.put("Replier", "Your parcel shipped yesterday.");
        return Runs.run(dir.resolve("store"), "r1", s, body, Map.of("message", message));
    }

    @Test
    void aScriptThatIsGoneIsSaid() throws Exception {
        Path r = run("hi");
        RunRecord rec = StoreReader.open(r);
        Files.delete(rec.script());
        assertThatThrownBy(() -> Capture.capture(rec, Capture.Options.defaults(), false))
                .hasMessageContaining("is not there any more");
    }

    @Test
    void aScriptThatChangedSinceTheRunIsRefusedWithTheHashThatRan() throws Exception {
        Path r = run("hi");
        RunRecord rec = StoreReader.open(r);
        Files.writeString(rec.script(), Files.readString(rec.script()) + "\n// edited\n");
        assertThatThrownBy(() -> Capture.capture(rec, Capture.Options.defaults(), false))
                .hasMessageContaining("ran a different version")
                .hasMessageContaining(rec.scriptHash().substring(0, 12));
    }

    @Test
    void aJournalWithoutTheAnswerTheRunNeededCannotBeReproduced() throws Exception {
        Path r = run("hi");
        Map<String, Object> journal =
                JSON.readValue(
                        r.resolve("journal.json").toFile(),
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
        journal.keySet().removeIf(k -> k.contains("a0")); // the Replier's answer
        JSON.writeValue(r.resolve("journal.json").toFile(), journal);
        RunRecord rec = StoreReader.open(r);
        assertThatThrownBy(() -> Capture.capture(rec, Capture.Options.defaults(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("r1");
    }

    @Test
    void aFailedCallIsScriptedAsAFailureNotAnAnswer() throws Exception {
        Path s = Runs.project(dir.resolve("p2"), Runs.TRIAGE);
        ObjectNode body = JSON.createObjectNode();
        body.putObject("input").put("message", "where is my parcel?");
        var models = body.putObject("models");
        models.putObject("Intent").put("kind", "order_status").put("urgent", "no");
        body.putObject("fail")
                .putObject("Replier")
                .put("always", true)
                .put("category", "server_error");
        body.putObject("expect").put("final", "failed");
        Path r =
                Runs.run(
                        dir.resolve("store2"),
                        "r2",
                        s,
                        body,
                        Map.of("message", "where is my parcel?"));
        Capture.Captured c =
                Capture.capture(StoreReader.open(r), Capture.Options.defaults(), false);
        assertThat(c.text()).contains("fail:").contains("Replier").contains("server_error");
        assertThat(c.text()).contains("final: failed");
    }

    @Test
    void anExistingDifferentFileIsAConflictAndNothingIsOverwritten() throws Exception {
        RunRecord rec = StoreReader.open(run("hi"));
        Capture.Captured first = Capture.capture(rec, Capture.Options.defaults(), true);
        Files.writeString(first.file(), Files.readString(first.file()) + "# edited by hand\n");
        Capture.Captured again = Capture.capture(rec, Capture.Options.defaults(), true);
        assertThat(again.conflict()).isTrue();
        assertThat(again.written()).isFalse();
        assertThat(Files.readString(first.file())).endsWith("# edited by hand\n");
    }

    @Test
    void denyFieldsAndAGivenNameAndFolderAreHonoured() throws Exception {
        RunRecord rec = StoreReader.open(run("hi"));
        Path out = dir.resolve("elsewhere");
        Capture.Captured c =
                Capture.capture(
                        rec,
                        new Capture.Options(
                                out,
                                Capture.Expect.NONE,
                                null,
                                null,
                                java.util.Set.of(),
                                true,
                                "why it matters",
                                java.util.List.of("t1"),
                                "My case!"),
                        true);
        assertThat(c.file()).hasParent(out).hasFileName("My-case-.test.yaml");
        assertThat(c.text()).contains("why it matters").contains("t1");
    }
}
