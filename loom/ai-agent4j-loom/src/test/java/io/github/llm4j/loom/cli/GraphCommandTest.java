package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** V3.1 to V3.5: {@code weave graph} prints only the result on stdout and touches nothing outside the files. */
class GraphCommandTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final AtomicInteger outsideCalls = new AtomicInteger();

    /** Anything beyond output streams (models, people, secrets, environment, commands) counts as a call. */
    private WeaveEnv env() {
        return new WeaveEnv(
                m -> {
                    outsideCalls.incrementAndGet();
                    throw new IllegalStateException("models");
                },
                q -> {
                    outsideCalls.incrementAndGet();
                    return "";
                },
                new PrintStream(out, true),
                new PrintStream(err, true),
                Clock.systemUTC(),
                d -> outsideCalls.incrementAndGet(),
                c -> {
                    outsideCalls.incrementAndGet();
                    return new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", "");
                },
                List.of("weave"),
                name -> {
                    outsideCalls.incrementAndGet();
                    return null;
                });
    }

    private GraphCommand command(String script, String format, String workflow) {
        GraphCommand c = new GraphCommand();
        c.script = new File(script);
        c.format = format;
        c.workflow = workflow;
        return c;
    }

    @Test
    void jsonIsTheDefaultAndStdoutHoldsNothingElse() throws Exception {
        int code = GraphCommand.graph(command("samples/content_factory/main.loom", "json", null), env());

        assertThat(code).isZero();
        JsonNode json = new ObjectMapper().readTree(out.toString());
        assertThat(json.get("version").asInt()).isEqualTo(1);
        assertThat(json.get("workflows").size()).isEqualTo(1);
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void mermaidPrintsAFlowchartPerWorkflowWithACommentNamingIt() {
        int code = GraphCommand.graph(command("src/test/resources/graph/diamond/a.loom", "mermaid", null), env());

        assertThat(code).isZero();
        assertThat(out.toString()).contains("%% workflow A (").contains("%% workflow D (");
        assertThat(out.toString().split("flowchart TD", -1)).hasSize(5);
    }

    @Test
    void oneWorkflowCanBeChosenAndAnUnknownNameListsTheValidOnes() throws Exception {
        assertThat(GraphCommand.graph(command("src/test/resources/graph/diamond/a.loom", "json", "D"), env())).isZero();
        JsonNode json = new ObjectMapper().readTree(out.toString());
        assertThat(json.get("workflows").size()).isEqualTo(1);
        assertThat(json.get("workflows").get(0).get("name").asText()).isEqualTo("D");

        out.reset();
        assertThat(GraphCommand.graph(command("src/test/resources/graph/diamond/a.loom", "json", "Nope"), env())).isEqualTo(2);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).contains("no workflow named Nope").contains("A, B, D, C");
    }

    @Test
    void itNeverReachesForAModelAPersonASecretTheEnvironmentOrACommand() {
        GraphCommand.graph(command("samples/boardroom/main.loom", "json", null), env());
        GraphCommand.graph(command("samples/digest/digest.loom", "mermaid", null), env());

        assertThat(outsideCalls.get()).isZero();
    }

    @Test
    void aMissingEntryFileExitsTwoWithNothingOnStdout() {
        int code = GraphCommand.graph(command(dir.resolve("none.loom").toString(), "json", null), env());

        assertThat(code).isEqualTo(2);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).contains("Error:");
    }

    @Test
    void aSyntaxErrorInTheEntryFileExitsTwoWithTheLineAndNoPartialJson() throws Exception {
        Path bad = Files.writeString(dir.resolve("bad.loom"), "workflow W() {\n  delegate \"x\" to A }\n");

        int code = GraphCommand.graph(command(bad.toString(), "json", null), env());

        assertThat(code).isEqualTo(2);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).contains("bad.loom:2");
    }

    @Test
    void aPartialGraphExitsZeroAndKeepsTheProblemInsideTheJson() throws Exception {
        int code = GraphCommand.graph(command("src/test/resources/graph/missing_import.loom", "json", null), env());

        assertThat(code).isZero();
        JsonNode json = new ObjectMapper().readTree(out.toString());
        assertThat(json.get("diagnostics").get(0).get("severity").asText()).isEqualTo("error");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void anUnknownFormatIsRefused() {
        assertThat(GraphCommand.graph(command("samples/content_factory/main.loom", "svg", null), env())).isEqualTo(2);
        assertThat(err.toString()).contains("--format takes json or mermaid");
    }

    @Test
    void theCommandIsRegisteredUnderWeave() {
        assertThat(WeaveCLI.commandLine().getSubcommands()).containsKey("graph");
    }

    @Test
    void theCommandWritesNoFiles() throws Exception {
        Path before = Path.of("samples/content_factory");
        long count;
        try (var files = Files.walk(before)) {
            count = files.count();
        }

        GraphCommand.graph(command("samples/content_factory/main.loom", "json", null), env());

        try (var files = Files.walk(before)) {
            assertThat(files.count()).isEqualTo(count);
        }
    }
}
