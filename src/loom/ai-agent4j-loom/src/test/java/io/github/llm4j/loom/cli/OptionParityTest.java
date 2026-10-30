package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Phase 22 R1: {@code weave run}, {@code eval} and {@code test} take the same options for the
 * same things, and the ones that are shared are defined once (a mixin), so their help cannot drift.
 */
class OptionParityTest {

    private static final List<String> SHARED =
            List.of("--classes", "--env-file", "--no-env-file", "--loot", "--no-auto-classes");

    private static CommandLine command(String name) {
        return WeaveCLI.commandLine().getSubcommands().get(name);
    }

    @Test
    void runEvalAndTestTakeTheSameClassesLootAndEnvFileOptions() {
        for (String name : List.of("run", "eval", "test"))
            for (String option : SHARED)
                assertThat(command(name).getCommandSpec().optionsMap())
                        .as("weave " + name + " " + option)
                        .containsKey(option);
    }

    @Test
    void theSharedOptionsHaveTheSameHelpInAllThree() {
        for (String option : List.of("--classes", "--env-file", "--no-env-file", "--no-auto-classes")) {
            String run = String.join(" ", command("run").getCommandSpec().optionsMap().get(option).description());
            for (String other : List.of("eval", "test"))
                assertThat(String.join(" ", command(other).getCommandSpec().optionsMap().get(option).description()))
                        .as(option + " in weave " + other)
                        .isEqualTo(run);
        }
    }

    @Test
    void aMissingLootFileIsRefusedSayingSo(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path script = java.nio.file.Files.writeString(dir.resolve("main.loom"), "workflow W() {\n return \"x\"\n}\n");
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        java.io.PrintStream old = System.err;
        System.setErr(new java.io.PrintStream(err, true));
        try {
            int code = WeaveCLI.commandLine().execute("test", script.toString(), "--loot", dir.resolve("nope.loot").toString());
            assertThat(code).isEqualTo(2);
        } finally {
            System.setErr(old);
        }
        assertThat(err.toString()).contains("--loot").contains("does not exist");
    }

    private static String stderrOf(String... args) {
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        java.io.PrintStream old = System.err;
        System.setErr(new java.io.PrintStream(err, true));
        try {
            WeaveCLI.commandLine().execute(args);
        } finally {
            System.setErr(old);
        }
        return err.toString();
    }

    @Test
    void aBuiltMavenProjectsClassesAreUsedAndTheTestSaysSoInOneLine(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path script = java.nio.file.Files.writeString(dir.resolve("main.loom"), "workflow W() {\n return \"x\"\n}\n");
        java.nio.file.Files.createDirectories(dir.resolve("target/classes"));

        String said = stderrOf("test", script.toString(), "--check");

        assertThat(said).contains("note: using the project's classes from").contains("target/classes").contains("--no-auto-classes");
        assertThat(said.lines().filter(l -> l.contains("using the project's classes")).count()).isEqualTo(1);
    }

    @Test
    void noAutoClassesTurnsItOffAndGivenClassesAreNotAnnounced(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path script = java.nio.file.Files.writeString(dir.resolve("main.loom"), "workflow W() {\n return \"x\"\n}\n");
        java.nio.file.Files.createDirectories(dir.resolve("target/classes"));

        assertThat(stderrOf("test", script.toString(), "--check", "--no-auto-classes")).doesNotContain("using the project's classes");
        assertThat(stderrOf("test", script.toString(), "--check", "--classes", dir.resolve("target/classes").toString()))
                .doesNotContain("using the project's classes");
    }
}
