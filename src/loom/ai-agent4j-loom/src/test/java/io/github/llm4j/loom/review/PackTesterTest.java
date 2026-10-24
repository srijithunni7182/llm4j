package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.pack.Pack;
import io.github.llm4j.loom.review.pack.PackLoader;
import io.github.llm4j.loom.review.pack.PackTester;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R68 of spec 09: a pack is tested by the examples its rules carry, and a wrong example is found.
 */
class PackTesterTest {

    @TempDir Path dir;

    static final String FIRES =
            "agent A { model: \"m\"  system: \"s\" }\nworkflow Main(q) { delegate \"{q}\" to A -> r  note \"{r}\" }\n";
    static final String QUIET =
            "agent A { model: \"m\"  system: \"s\" }\nworkflow Main(q) { note \"hi\" }\n";

    Pack pack(String examples) throws IOException {
        Files.createDirectories(dir.resolve("rules"));
        Files.createDirectories(dir.resolve("examples"));
        Files.writeString(
                dir.resolve("pack.yaml"),
                "name: acme\nversion: 1.0.0\nfactModel: 1\nrulesSchema: 1\nprefix: ACME\n");
        Files.writeString(dir.resolve("examples/fires.loom"), FIRES);
        Files.writeString(dir.resolve("examples/quiet.loom"), QUIET);
        Files.writeString(
                dir.resolve("rules/r.yaml"),
                "id: ACME-001\nlens: reliability\nseverity: low\nabout: step\nwhen: { field: kind, is: delegate }\ntitle: t\nfixHint: h\nexamples:\n"
                        + examples);
        return PackLoader.directory(dir);
    }

    @Test
    void examplesThatDoWhatTheySayPass() throws Exception {
        List<PackTester.Result> r =
                PackTester.run(
                        pack(
                                "  fires: [{ file: examples/fires.loom, expect: 1 }]\n  quiet: [examples/quiet.loom]\n"));
        assertThat(r).hasSize(2).allMatch(PackTester.Result::passed);
        assertThat(r.get(0).message()).contains("1 finding");
    }

    @Test
    void aFiringExampleThatDoesNotFireIsAFailureWithTheCount() throws Exception {
        List<PackTester.Result> r =
                PackTester.run(
                        pack("  fires: [examples/quiet.loom]\n  quiet: [examples/quiet.loom]\n"));
        assertThat(r.get(0).passed()).isFalse();
        assertThat(r.get(0).message())
                .contains("expected at least one finding but the rule reported 0");
    }

    @Test
    void aQuietExampleThatFiresAndAWrongExpectedCountAreFailures() throws Exception {
        List<PackTester.Result> r =
                PackTester.run(
                        pack(
                                "  fires: [{ file: examples/fires.loom, expect: 3 }]\n  quiet: [examples/fires.loom]\n"));
        assertThat(r.get(0).message()).contains("expected 3 finding(s) but the rule reported 1");
        assertThat(r.get(1).passed()).isFalse();
        assertThat(r.get(1).message()).contains("expected no findings but the rule reported 1");
    }

    @Test
    void aMissingExampleFileAndAnUnparseableOneAreFailuresNotCrashes() throws Exception {
        List<PackTester.Result> r =
                PackTester.run(
                        pack(
                                "  fires: [examples/nope.loom, { inline: \"this is not loom {{{\" }]\n  quiet: [examples/quiet.loom]\n"));
        assertThat(r.get(0).passed()).isFalse();
        assertThat(r.get(0).message()).contains("is missing from the pack");
        assertThat(r.get(1).passed()).isFalse();
        assertThat(r.get(1).message()).contains("could not be reviewed");
        assertThat(r.get(2).passed()).isTrue();
    }

    @Test
    void examplesRunWithAllLensesAndHeuristicsOnSoNoRuleHidesBehindADefault() throws Exception {
        Files.createDirectories(dir.resolve("rules"));
        Pack p = pack("  fires: [examples/fires.loom]\n  quiet: [examples/quiet.loom]\n");
        Files.writeString(
                dir.resolve("rules/r.yaml"),
                Files.readString(dir.resolve("rules/r.yaml"))
                                .replace("lens: reliability", "lens: governance")
                        + "heuristic: true\n");
        Pack governed = PackLoader.directory(dir);
        assertThat(PackTester.run(governed)).allMatch(PackTester.Result::passed);
        assertThat(p.rules()).hasSize(1);
    }
}
