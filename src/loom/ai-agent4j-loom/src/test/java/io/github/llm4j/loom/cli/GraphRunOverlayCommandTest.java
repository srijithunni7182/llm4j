package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.travel.TravelFixtures;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** R34 to R37 of spec 13 through {@code weave graph --run}. */
class GraphRunOverlayCommandTest {

    @TempDir Path dir;

    static WhyCommandTest.Out graph(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        m -> {
                            throw new IllegalStateException("weave graph calls no model");
                        },
                        q -> {
                            throw new IllegalStateException("weave graph asks no one");
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
        GraphCommand c = new GraphCommand();
        new CommandLine(c).parseArgs(args);
        int code = GraphCommand.graph(c, env);
        return new WhyCommandTest.Out(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path runOf(String name) throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals(name))
                        .findFirst()
                        .orElseThrow();
        return TravelFixtures.runDir(dir, TravelFixtures.run(dir, c));
    }

    @Test
    void oneRunIsDrawnOnTheGraphAsJson() throws Exception {
        Path run = runOf("triage order status");
        var o = graph(dir.resolve("triage-order-status.loom").toString(), "--run", run.toString());
        assertThat(o.code()).isZero();
        JsonNode overlay = new ObjectMapper().readTree(o.out()).get("overlay");
        assertThat(overlay.get("runs").get(0).asText()).startsWith("run-");
        assertThat(overlay.get("decisions")).isNotEmpty();
        assertThat(overlay.get("workflows").get("Main").get("nodes").size()).isGreaterThan(3);
    }

    @Test
    void severalRunsListTheStatementsNoneReached() throws Exception {
        Path a = runOf("triage order status");
        // the same script (same file name, same text), run again in another place with another
        // outcome
        var other =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals("triage other"))
                        .findFirst()
                        .orElseThrow();
        Path second = Files.createDirectory(dir.resolve("again"));
        Path b =
                TravelFixtures.runDir(
                        second,
                        TravelFixtures.run(
                                second,
                                new TravelFixtures.Case(
                                        "triage order status",
                                        other.script(),
                                        other.workflow(),
                                        other.input(),
                                        other.scripted())));
        // both runs are of the same script text, so the same graph answers for both
        var o =
                graph(
                        dir.resolve("triage-order-status.loom").toString(),
                        "--run",
                        a.toString(),
                        "--run",
                        b.toString());
        assertThat(o.code()).isZero();
        JsonNode overlay = new ObjectMapper().readTree(o.out()).get("overlay");
        assertThat(overlay.get("runs")).hasSize(2);
        assertThat(overlay.get("decisions")).isEmpty();
        assertThat(overlay.get("neverReached").toString()).contains("note");
    }

    @Test
    void mermaidDrawsThePathBold() throws Exception {
        Path run = runOf("triage order status");
        var o =
                graph(
                        dir.resolve("triage-order-status.loom").toString(),
                        "--run",
                        run.toString(),
                        "--format",
                        "mermaid");
        assertThat(o.code()).isZero();
        assertThat(o.out()).contains("==>").contains("-.->").contains("classDef ran");
    }

    @Test
    void aRunOfAnotherVersionOfTheScriptIsRefused() throws Exception {
        Path run = runOf("triage order status");
        Path changed = dir.resolve("changed.loom");
        Files.writeString(
                changed,
                Files.readString(dir.resolve("triage-order-status.loom"))
                        .replace("urgent", "angry"));
        var o = graph(changed.toString(), "--run", run.toString());
        assertThat(o.code()).isEqualTo(2);
        assertThat(o.err()).contains("ran a different version");
    }

    @Test
    void aDirectoryThatIsNotARunIsRefusedWithAReason() throws Exception {
        runOf("triage order status");
        var o =
                graph(
                        dir.resolve("triage-order-status.loom").toString(),
                        "--run",
                        dir.resolve("nowhere").toString());
        assertThat(o.code()).isEqualTo(2);
        assertThat(o.err()).contains("nowhere");
    }

    @Test
    void withoutRunTheOutputIsExactlyWhatItWas() throws Exception {
        runOf("triage order status");
        var o = graph(dir.resolve("triage-order-status.loom").toString());
        assertThat(o.out()).doesNotContain("overlay");
    }
}
