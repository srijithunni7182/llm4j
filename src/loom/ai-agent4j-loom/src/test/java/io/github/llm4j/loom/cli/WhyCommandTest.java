package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.travel.TravelFixtures;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * R3, R7, R11, R12 of spec 13 through the command: the explanation of a run directory, in three
 * formats, refusing a changed script, writing nothing and needing nothing.
 */
class WhyCommandTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;

    record Out(int code, String out, String err) {}

    static Out why(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        m -> {
                            throw new IllegalStateException("weave why calls no model");
                        },
                        q -> {
                            throw new IllegalStateException("weave why asks no one");
                        },
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        WhyCommand c = new WhyCommand();
        new CommandLine(c).parseArgs(args);
        int code = WhyCommand.why(c, env);
        return new Out(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path runDirOf(String name) throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals(name))
                        .findFirst()
                        .orElseThrow();
        return TravelFixtures.runDir(dir, TravelFixtures.run(dir, c));
    }

    static String hashOf(Path folder) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> all = Files.walk(folder)) {
            for (Path p : all.filter(Files::isRegularFile).sorted().toList()) {
                d.update(folder.relativize(p).toString().getBytes(StandardCharsets.UTF_8));
                d.update(Files.readAllBytes(p));
            }
        }
        return HexFormat.of().formatHex(d.digest());
    }

    @Test
    void explainsARunFromItsDirectory() throws Exception {
        Path run = runDirOf("triage order status");
        Out o = why(run.toString());
        assertThat(o.code()).isZero();
        assertThat(o.out())
                .contains("alt (intent.urgent == \"yes\")")
                .contains("(bound at step 1 by intentclassify Intent)");
        assertThat(o.err()).isEmpty();
    }

    @Test
    void aRunIsFoundByIdInTheStore() throws Exception {
        Path store = dir.resolve("store");
        Path runs = store.resolve("runs");
        Files.createDirectories(runs);
        Path run = runDirOf("straight");
        Path target = runs.resolve("job-1");
        try (Stream<Path> all = Files.walk(run)) {
            for (Path p : all.toList()) {
                Path to = target.resolve(run.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        Out o = why("job-1", "--store", store.toString());
        assertThat(o.code()).isZero();
        assertThat(o.out()).contains("delegate A");
    }

    @Test
    void jsonIsVersionedAndCarriesTheSameChoices() throws Exception {
        Path run = runDirOf("triage urgent");
        JsonNode j = JSON.readTree(why(run.toString(), "--format", "json").out());
        assertThat(j.path("version").asInt()).isEqualTo(1);
        assertThat(j.path("status").asText()).isEqualTo("completed");
        JsonNode alt = j.path("entries").get(1);
        assertThat(alt.path("kind").asText()).isEqualTo("alt");
        assertThat(alt.path("condition").asText()).isEqualTo("intent.urgent == \"yes\"");
        assertThat(alt.path("result").asBoolean()).isTrue();
        assertThat(alt.path("operands").get(0).path("boundBy").asText())
                .isEqualTo("intentclassify Intent");
        assertThat(alt.path("operands").get(0).path("boundNode").asText()).startsWith("n");
        assertThat(j.path("entries").get(0).fieldNames())
                .toIterable()
                .contains("n", "step", "line", "kind", "statement");
    }

    @Test
    void markdownIsATableOfTheChoices() throws Exception {
        String md = why(runDirOf("triage urgent").toString(), "--format", "md").out();
        assertThat(md)
                .contains("| # | Line | Statement |")
                .contains("`alt (intent.urgent == \"yes\")`");
    }

    @Test
    void askingAboutALineAnswersWhyItRanOrDidNot() throws Exception {
        Path run = runDirOf("triage order status");
        assertThat(why(run.toString(), "14").out()).contains("delegate Replier ran because");
        assertThat(why(run.toString(), "--line", "10").out()).contains("did not run");
        assertThat(why(run.toString(), "Main/s1/b0/a0").out()).contains("ran because");
    }

    @Test
    void aChangedScriptIsRefusedUnlessTheOldOneIsGiven() throws Exception {
        var c = TravelFixtures.CORPUS.get(1);
        var real = TravelFixtures.run(dir, c);
        Path run = TravelFixtures.runDir(dir, real);
        Path original = dir.resolve("original.loom");
        Files.copy(real.script(), original);
        Files.writeString(real.script(), Files.readString(real.script()) + "\n// edited\n");
        Out refused = why(run.toString());
        assertThat(refused.code()).isEqualTo(2);
        assertThat(refused.err())
                .contains("has changed since this run started")
                .contains("--script old.loom");
        assertThat(refused.out()).isEmpty();
        Out given = why(run.toString(), "--script", original.toString());
        assertThat(given.code()).isZero();
        assertThat(given.out()).contains("Explained against").contains("alt (intent.urgent");
    }

    @Test
    void aRunThatRecordedNoFingerprintIsExplainedWithANote() throws Exception {
        Path run = runDirOf("straight");
        JsonNode spec = JSON.readTree(run.resolve("run.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) spec).remove("scriptHash");
        Files.writeString(run.resolve("run.json"), spec.toString());
        Out o = why(run.toString());
        assertThat(o.code()).isZero();
        assertThat(o.out()).contains("did not record which script it used");
    }

    @Test
    void nothingIsWrittenToTheRunDirectory() throws Exception {
        Path run = runDirOf("loop until done");
        String before = hashOf(run);
        long files;
        try (Stream<Path> all = Files.walk(run)) {
            files = all.count();
        }
        why(run.toString());
        why(run.toString(), "--format", "json", "--all");
        why(run.toString(), "4");
        assertThat(hashOf(run)).isEqualTo(before);
        try (Stream<Path> all = Files.walk(run)) {
            assertThat(all.count()).isEqualTo(files);
        }
    }

    @Test
    void theSameRunExplainedTwiceIsTheSameBytes() throws Exception {
        Path run = runDirOf("rewind");
        for (String format : List.of("text", "md", "json"))
            assertThat(why(run.toString(), "--format", format).out())
                    .isEqualTo(why(run.toString(), "--format", format).out());
    }

    @Test
    void aBadFormatAndAMissingRunAreUsageErrors() throws Exception {
        assertThat(why(runDirOf("straight").toString(), "--format", "xml").code()).isEqualTo(2);
        Out missing = why(dir.resolve("nope").toString());
        assertThat(missing.code()).isEqualTo(2);
        assertThat(missing.err()).contains("is not a run directory");
        assertThat(new File(dir.resolve("nope").toString())).doesNotExist();
    }

    @Test
    void valuesThatAreHostileAreShownAsEscapedText() throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals("triage other"))
                        .findFirst()
                        .orElseThrow();
        var hostile =
                new TravelFixtures.Case(
                        "hostile",
                        c.script(),
                        "Main",
                        java.util.Map.of("message", "\u001b[31mred\u001b[0m\n</script><b>x</b> ‮"),
                        x ->
                                x.classifier(
                                        "Intent",
                                        java.util.Map.of("kind", "other", "urgent", "no")));
        Path run = TravelFixtures.runDir(dir, TravelFixtures.run(dir, hostile));
        String text = why(run.toString(), "--all").out();
        assertThat(text).doesNotContain("\u001b").doesNotContain("‮");
        assertThat(why(run.toString(), "--format", "md").out())
                .doesNotContain("<b>")
                .doesNotContain("\u001b");
    }
}
