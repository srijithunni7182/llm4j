package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A developer's keys in a .env beside the script: read, never printed, the shell wins, and a file git tracks is refused. */
@Tag("fragile")
class EnvFileTest {

    @TempDir Path dir;

    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();

    WeaveEnv env(Map<String, String> shell) {
        return new WeaveEnv(m -> null, q -> "yes", new PrintStream(out, true), new PrintStream(err, true), Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), shell::get);
    }

    private static final String SECRET = "sk-test-value-that-must-never-be-printed";

    private Path write(String name, String text) throws Exception {
        Path f = Files.writeString(dir.resolve(name), text);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        return f;
    }

    private static boolean git(Path where, String... args) throws Exception {
        List<String> c = new java.util.ArrayList<>(List.of("git", "-C", where.toString()));
        c.addAll(List.of(args));
        return new ProcessBuilder(c).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
    }

    // ── the format ───────────────────────────────────────────────────────────

    @Test
    void parsesNameValueLinesWithQuotesExportCommentsAndIgnoresEmptyValues() {
        Map<String, String> keys = EnvFile.parse("""
                # my keys
                GEMINI_API_KEY=abc123
                export ANTHROPIC_API_KEY = "quoted value"   
                SINGLE='single quoted'
                WITH_COMMENT=plain # trailing note

                EMPTY=
                """, ".env");

        assertThat(keys).containsExactly(Map.entry("GEMINI_API_KEY", "abc123"), Map.entry("ANTHROPIC_API_KEY", "quoted value"),
                Map.entry("SINGLE", "single quoted"), Map.entry("WITH_COMMENT", "plain"));
    }

    @Test
    void aBadLineIsReportedByNumberAndNeverShowsItsContent() {
        assertThatThrownBy(() -> EnvFile.parse("OK=1\nthis is " + SECRET + "\n", ".env")).hasMessageContaining(".env line 2").hasMessageNotContaining(SECRET);
    }

    // ── using it ─────────────────────────────────────────────────────────────

    @Test
    void theFilesKeysAreFoundTheShellWinsAndOnlyNamesAreSaid() throws Exception {
        Path file = write(".env", "GEMINI_API_KEY=" + SECRET + "\nOTHER=from-file\n");

        WeaveEnv with = EnvFile.apply(env(Map.of("OTHER", "from-shell")), file, false);

        assertThat(with.env().apply("GEMINI_API_KEY")).isEqualTo(SECRET);
        assertThat(with.env().apply("OTHER")).isEqualTo("from-shell");
        assertThat(with.env().apply("NOT_THERE")).isNull();
        assertThat(err.toString()).contains("Using keys from .env: GEMINI_API_KEY, OTHER").doesNotContain(SECRET).doesNotContain("from-file");
        assertThat(out.toString()).doesNotContain(SECRET);
    }

    @Test
    void aFileGitTracksIsRefusedAndTheMessageSaysWhatToDo() throws Exception {
        Path file = write(".env", "GEMINI_API_KEY=" + SECRET + "\n");
        assertThat(git(dir, "init", "-q")).isTrue();
        assertThat(git(dir, "add", ".env")).isTrue();

        WeaveEnv with = EnvFile.apply(env(Map.of()), file, false);

        assertThat(with).isNull();
        assertThat(err.toString()).contains("is tracked by git").contains("git rm --cached .env").contains("rotate").doesNotContain(SECRET);
    }

    @Test
    void aFileInARepositoryThatIsNotIgnoredGetsAWarningAndAnIgnoredOneDoesNot() throws Exception {
        Path file = write(".env", "A_KEY=1\n");
        assertThat(git(dir, "init", "-q")).isTrue();

        assertThat(EnvFile.warnings(file)).anyMatch(w -> w.contains("is not in .gitignore"));

        Files.writeString(dir.resolve(".gitignore"), ".env\n");
        assertThat(EnvFile.warnings(file)).noneMatch(w -> w.contains("gitignore"));
    }

    @Test
    void aFileOtherUsersCanReadGetsAWarningWithTheFix() throws Exception {
        Path file = write(".env", "A_KEY=1\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(EnvFile.warnings(file)).anyMatch(w -> w.contains("chmod 600 .env"));

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        assertThat(EnvFile.warnings(file)).noneMatch(w -> w.contains("chmod"));
    }

    @Test
    void anExplicitFileThatIsMissingIsAnError() {
        WeaveEnv with = EnvFile.apply(env(Map.of()), dir.resolve("nope.env"), true);

        assertThat(with).isNull();
        assertThat(err.toString()).contains("was not found");
    }

    // ── through the command line, as a person would use it ───────────────────

    private int run(String... args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        try {
            return WeaveCLI.commandLine().execute(args);
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    @Test
    void checkOfAStarterNeedsTheKeyAndFindsItInTheEnvFileBesideTheScript() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") == null, "the shell already has the key");
        Path project = dir.resolve("p");
        assertThat(run("init", "pipeline", project.toString(), "--flat")).isZero();
        out.reset();
        err.reset();

        assertThat(run("check", project.resolve("main.loom").toString())).as(out.toString()).isEqualTo(2);

        out.reset();
        err.reset();
        Files.writeString(project.resolve(".env"), "GEMINI_API_KEY=" + SECRET + "\n");
        Files.setPosixFilePermissions(project.resolve(".env"), PosixFilePermissions.fromString("rw-------"));
        assertThat(run("check", project.resolve("main.loom").toString())).as(out + "\n" + err).isZero();
        assertThat(err.toString()).contains("Using keys from .env: GEMINI_API_KEY").doesNotContain(SECRET);
        assertThat(out.toString()).doesNotContain(SECRET);

        out.reset();
        err.reset();
        assertThat(run("check", project.resolve("main.loom").toString(), "--no-env-file")).isEqualTo(2);
    }

    @Test
    void anExplicitEnvFileCanBeNamed() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") == null, "the shell already has the key");
        Path project = dir.resolve("q");
        assertThat(run("init", "classifier", project.toString(), "--flat")).isZero();
        Path keys = write("elsewhere.env", "GEMINI_API_KEY=" + SECRET + "\n");
        out.reset();
        err.reset();

        assertThat(run("check", project.resolve("main.loom").toString(), "--env-file", keys.toString())).as(out + "\n" + err).isZero();
    }
}
