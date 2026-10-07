package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** weave init gives a developer the .env they will need, already ignored by git; next says what to do about the key; the guide keeps the two people apart. */
class InitKeyFilesTest {

    @TempDir Path dir;

    private int run(ByteArrayOutputStream out, String... args) {
        PrintStream old = System.out;
        System.setOut(new PrintStream(out, true));
        try {
            return WeaveCLI.commandLine().execute(args);
        } finally {
            System.setOut(old);
        }
    }

    @Test
    void everyStarterWritesAnEnvExampleWithItsKeyNamesAndAGitignoreThatIgnoresTheRealFile() throws Exception {
        for (String t : new String[] {"pipeline", "approval", "classifier"}) {
            Path p = dir.resolve(t);
            assertThat(run(new ByteArrayOutputStream(), "init", t, p.toString())).isZero();

            assertThat(Files.readString(p.resolve(".env.example"))).as(t).contains("GEMINI_API_KEY=").contains("cp .env.example .env");
            assertThat(Files.readAllLines(p.resolve(".gitignore"))).as(t).contains(".env", ".env.*", "!.env.example", "*.store");
            assertThat(p.resolve(".env")).as("init never creates the file that holds a key").doesNotExist();
        }
    }

    @Test
    void anExistingGitignoreIsAddedToNotReplacedAndNotAConflict() throws Exception {
        Path p = Files.createDirectories(dir.resolve("existing"));
        Files.writeString(p.resolve(".gitignore"), "target/\n.env\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(run(out, "init", "pipeline", p.toString())).isZero();

        assertThat(Files.readAllLines(p.resolve(".gitignore"))).contains("target/", ".env", ".env.*", "!.env.example", "*.store");
        assertThat(Files.readAllLines(p.resolve(".gitignore")).stream().filter(".env"::equals).count()).as("no line is repeated").isEqualTo(1);
        assertThat(p.resolve("main.loom")).exists();
    }

    @Test
    void theExampleFileCopiedAsIsDoesNotHideAKeyThatIsSetInTheShell() throws Exception {
        Path p = dir.resolve("copy");
        run(new ByteArrayOutputStream(), "init", "pipeline", p.toString());
        Path env = Files.copy(p.resolve(".env.example"), p.resolve(".env"));
        Files.setPosixFilePermissions(env, PosixFilePermissions.fromString("rw-------"));

        assertThat(EnvFile.parse(Files.readString(env), ".env")).as("every value in the example is empty, so nothing is taken from it").isEmpty();
    }

    @Test
    void nextSaysToCopyTheExampleThenToFillItInAndThenNothingAboutKeys() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") == null, "the shell already has the key");
        Path p = dir.resolve("n");
        run(new ByteArrayOutputStream(), "init", "pipeline", p.toString());

        ByteArrayOutputStream first = new ByteArrayOutputStream();
        run(first, "next", p.toString());
        assertThat(first.toString()).contains("copy .env.example to .env").contains("$ cp .env.example .env");

        Path env = Files.copy(p.resolve(".env.example"), p.resolve(".env"));
        Files.setPosixFilePermissions(env, PosixFilePermissions.fromString("rw-------"));
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        run(second, "next", p.toString());
        assertThat(second.toString()).contains("Add GEMINI_API_KEY to .env").doesNotContain("$ cp .env.example .env");

        Files.writeString(env, "GEMINI_API_KEY=filled-in\n");
        ByteArrayOutputStream third = new ByteArrayOutputStream();
        run(third, "next", p.toString());
        assertThat(third.toString()).doesNotContain("GEMINI_API_KEY").doesNotContain(".env");
    }

    @Test
    void theGuideAndTheSkillKeepTheDeveloperAndTheDeployerApart() throws Exception {
        String chapter = Files.readString(Path.of("../../docs/guide/09-go-live.md"));
        String skill = Files.readString(Path.of("../../.claude/skills/llm4j-workflow-guide/SKILL.md"));

        assertThat(chapter).contains("## Keys and secrets: two different people").contains("cp .env.example .env").contains("`weave` refuses a `.env` that git tracks")
                .contains("Never a `.env` there").contains("weave secrets create --secrets /etc/myapp/keys.store");
        assertThat(skill).contains("**Keys: two different people, two different answers. Ask which one this is.**").contains("A developer running the workflow").contains("Someone deploying the application");
        assertThat(Files.readString(Path.of("LOOM_GUIDE.md"))).contains("**Keys for a developer's machine** go in a `.env` file beside the script");
    }
}
