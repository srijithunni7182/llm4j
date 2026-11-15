package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.mcp.testkit.FakeMcpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code weave mcp list} (phase 36a, D4 and D10): starts the server under the isolation its block
 * names, lists its tools with what the annotations suggest, labelled as the server's untrusted word,
 * and with {@code --write} puts the approved effects and a pin into the script, after showing the
 * change.
 */
class McpListCommandTest {

    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final List<String> asked = new ArrayList<>();

    WeaveEnv env(String answer) {
        return new WeaveEnv(
                m -> null,
                question -> {
                    asked.add(question);
                    return answer;
                },
                new PrintStream(out, true),
                new PrintStream(err, true),
                Clock.systemUTC(),
                d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"));
    }

    private Path script(String blockOptions) throws Exception {
        List<String> command = FakeMcpServer.command();
        List<String> quoted = new ArrayList<>();
        for (String a : command.subList(1, command.size())) quoted.add("\"" + a + "\"");
        Path file = dir.resolve("w.loom");
        Files.writeString(
                file,
                "mcp Fake {\n    transport: \"stdio\"\n    cmd: \""
                        + command.get(0)
                        + "\"\n    args: ["
                        + String.join(", ", quoted)
                        + "]\n    isolation: process\n"
                        + blockOptions
                        + "}\nagent A { model: \"m\" system: \"s\" mcp_servers: [Fake] }\nworkflow Main() { delegate \"x\" to A -> r }\n");
        return file;
    }

    private McpCommand.List command(Path file, boolean write, boolean yes) {
        McpCommand.List c = new McpCommand.List();
        c.script = file.toFile();
        c.write = write;
        c.yes = yes;
        return c;
    }

    @Test
    void listsEveryToolWithWhatTheAnnotationsSuggestAsTheServersWord() throws Exception {
        int code = McpCommand.List.list(command(script(""), false, false), env("no"));

        assertThat(code).isZero();
        String text = out.toString();
        assertThat(text)
                .contains("mcp Fake")
                .contains("isolation: process")
                .contains("echo")
                .contains("boom")
                .contains("read")
                .contains("untrusted")
                .contains("pin: sha256-");
        // `read` is the one tool the fake annotates read-only
        assertThat(lineOf(text, "read")).contains("reads").contains("readOnlyHint");
        assertThat(lineOf(text, "echo")).contains("changes").contains("no annotations");
    }

    private static String lineOf(String text, String tool) {
        return text.lines().filter(l -> l.stripLeading().startsWith(tool + " ")).findFirst().orElse("");
    }

    @Test
    void nothingIsWrittenWithoutWrite() throws Exception {
        Path file = script("");
        String before = Files.readString(file);

        McpCommand.List.list(command(file, false, false), env("yes"));

        assertThat(Files.readString(file)).isEqualTo(before);
        assertThat(asked).isEmpty();
    }

    @Test
    void writeShowsTheChangeAndAskedFirstAndANoLeavesTheScriptAlone() throws Exception {
        Path file = script("");
        String before = Files.readString(file);

        int code = McpCommand.List.list(command(file, true, false), env("no"));

        assertThat(code).isZero();
        assertThat(Files.readString(file)).isEqualTo(before);
        assertThat(asked).hasSize(1);
        assertThat(out.toString()).contains("+ ").contains("effects");
    }

    @Test
    void aYesWritesTheProposedEffectsAndAPinAndTheScriptStillLoads() throws Exception {
        Path file = script("");

        int code = McpCommand.List.list(command(file, true, false), env("yes"));

        assertThat(code).isZero();
        var block = new LoomParser(new Lexer(Files.readString(file)).tokenize()).parseScript().getMcpServers().get(0);
        assertThat(block.getToolEffects())
                .containsEntry("read", io.github.llm4j.agent.task.TaskEffect.READS)
                .containsEntry("echo", io.github.llm4j.agent.task.TaskEffect.CHANGES);
        assertThat(block.getPin()).startsWith("sha256-");
        assertThat(block.getIsolation()).isEqualTo("process");
    }

    @Test
    void yesSkipsTheQuestionButStillShowsTheChange() throws Exception {
        Path file = script("");

        McpCommand.List.list(command(file, true, true), env("no"));

        assertThat(asked).isEmpty();
        assertThat(out.toString()).contains("+ ");
        assertThat(Files.readString(file)).contains("pin:");
    }

    @Test
    void aDeclaredEffectIsKeptAndShownAsDeclared() throws Exception {
        Path file = script("    effects: { read: changes }\n");

        McpCommand.List.list(command(file, true, true), env("yes"));

        var block = new LoomParser(new Lexer(Files.readString(file)).tokenize()).parseScript().getMcpServers().get(0);
        assertThat(block.getToolEffects()).containsEntry("read", io.github.llm4j.agent.task.TaskEffect.CHANGES);
        assertThat(lineOf(out.toString(), "read")).contains("changes").contains("declared");
    }

    @Test
    void aPinThatStillMatchesIsSaidSoAndADifferentOneIsReported() throws Exception {
        Path file = script("");
        McpCommand.List.list(command(file, true, true), env("yes"));
        out.reset();

        McpCommand.List.list(command(file, false, false), env("no"));
        assertThat(out.toString()).contains("matches the pin");

        Files.writeString(file, Files.readString(file).replaceAll("pin: \"sha256-[0-9a-f]+\"", "pin: \"sha256-0000000000000000\""));
        out.reset();
        McpCommand.List.list(command(file, false, false), env("no"));
        assertThat(out.toString()).contains("differs from the pin");
    }

    @Test
    void aServerThatCannotStartIsAnErrorWithTheReason() throws Exception {
        Path file = dir.resolve("w.loom");
        Files.writeString(
                file,
                "mcp Fake { cmd: \"/no/such/program\" isolation: process }\nagent A { model: \"m\" system: \"s\" mcp_servers: [Fake] }\nworkflow Main() { delegate \"x\" to A -> r }\n");

        int code = McpCommand.List.list(command(file, false, false), env("no"));

        assertThat(code).isEqualTo(1);
        assertThat(err.toString()).contains("Fake").contains("/no/such/program");
    }

    @Test
    void anUnknownServerNameIsAnError() throws Exception {
        McpCommand.List c = command(script(""), false, false);
        c.server = "Nope";

        assertThat(McpCommand.List.list(c, env("no"))).isEqualTo(2);
        assertThat(err.toString()).contains("Nope").contains("Fake");
    }
}
