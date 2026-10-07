package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Small things a first-time user trips on: help that works everywhere, a project folder named in the next steps, a version that says which jar it is. */
class CommandLineFixesTest {

    @TempDir Path dir;

    @Test
    void secretsHelpPrintsItsUsageAndExitsZeroLikeTheOtherCommands() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CommandLine cli = WeaveCLI.commandLine();
        cli.setOut(new java.io.PrintWriter(out, true));
        cli.setErr(new java.io.PrintWriter(err, true));

        assertThat(cli.execute("secrets", "--help")).isZero();
        assertThat(out.toString()).contains("Usage: weave secrets");
        assertThat(err.toString()).doesNotContain("Unknown option");
    }

    @Test
    void initNamesTheFolderToMoveIntoBeforeTheNextCommands() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WeaveEnv env = new WeaveEnv(m -> { throw new IllegalStateException("no models"); }, q -> "yes", new PrintStream(out, true), new PrintStream(out, true),
                Clock.systemUTC(), d -> { }, c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), k -> null);
        InitCommand c = new InitCommand();
        new CommandLine(c).parseArgs("pipeline", dir.resolve("brandpost").toString());

        assertThat(InitCommand.init(c, env)).isZero();

        String text = out.toString();
        assertThat(text).contains("  cd " + dir.resolve("brandpost"));
        assertThat(text.indexOf("  cd ")).isLessThan(text.indexOf("weave check src/main/resources/main.loom --no-env"));
    }

    @Test
    void theVersionSaysItIsNotABuiltJarWhenRunFromClasses() {
        assertThat(new WeaveCLI.BuildVersion().getVersion()).singleElement().asString().startsWith("weave ");
    }

    @Test
    void evalPutsTheLoggingLevelBackAndShowsOnlyTheResultByDefault() throws Exception {
        java.util.logging.Logger loom = java.util.logging.Logger.getLogger("io.github.llm4j.loom");
        java.util.logging.Level before = loom.getLevel();
        EvalCommand c = new EvalCommand();
        new CommandLine(c).parseArgs(dir.resolve("none.loom").toString(), "--check");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WeaveEnv env = new WeaveEnv(m -> { throw new IllegalStateException("no models"); }, q -> "yes", new PrintStream(new ByteArrayOutputStream(), true),
                new PrintStream(err, true), Clock.systemUTC(), d -> { }, cmd -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), k -> null);

        assertThat(EvalCommand.eval(c, env)).isEqualTo(2);
        assertThat(loom.getLevel()).isEqualTo(before);
        assertThat(c.verbose).isFalse();
    }

    @Test
    void everyCommandAnswersHelpWithItsUsageAndExitCodeZero() {
        CommandLine root = WeaveCLI.commandLine();
        java.util.List<String[]> commands = new java.util.ArrayList<>();
        collect(root, new String[0], commands);
        assertThat(commands).hasSizeGreaterThan(20);
        for (String[] path : commands) {
            String[] args = java.util.Arrays.copyOf(path, path.length + 1);
            args[path.length] = "--help";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            CommandLine cli = WeaveCLI.commandLine();
            cli.setOut(new java.io.PrintWriter(out, true));
            cli.setErr(new java.io.PrintWriter(err, true));
            assertThat(cli.execute(args)).as(String.join(" ", args)).isZero();
            assertThat(out.toString()).as(String.join(" ", args)).contains("Usage:");
            assertThat(err.toString()).as(String.join(" ", args)).doesNotContain("Unknown option");
        }
    }

    private static void collect(CommandLine cli, String[] path, java.util.List<String[]> into) {
        for (var e : cli.getSubcommands().entrySet()) {
            String[] next = java.util.Arrays.copyOf(path, path.length + 1);
            next[path.length] = e.getKey();
            into.add(next);
            collect(e.getValue(), next, into);
        }
    }
}
