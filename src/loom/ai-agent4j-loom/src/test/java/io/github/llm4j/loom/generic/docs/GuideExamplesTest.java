package io.github.llm4j.loom.generic.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The guide's "Generic Tools" section is checked against the code: every example loads, every command runs. */
@Tag("fragile")
class GuideExamplesTest {

    static final Path GUIDE = Path.of("LOOM_GUIDE.md");

    @TempDir
    Path dir;

    @BeforeEach
    void directories() throws Exception {
        Files.createDirectories(dir.resolve("work")); // the shell example's cwd
    }

    /** The Loom code blocks between "### Generic Tools" and the next "###" heading of the same level. */
    static List<String> blocks() throws Exception {
        String guide = Files.readString(GUIDE, StandardCharsets.UTF_8);
        int from = guide.indexOf("### Generic Tools");
        int to = guide.indexOf("\n### ", from + 10);
        assertThat(from).as("the guide has a Generic Tools section").isGreaterThanOrEqualTo(0);
        String section = guide.substring(from, to);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("```loom\\n(.*?)```", Pattern.DOTALL).matcher(section);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    @Tag("V10.1")
    void everyLoomBlockInTheSectionParsesAndPassesLoadTimeChecks() throws Exception {
        List<String> blocks = blocks();
        assertThat(blocks.size()).as("blocks found in the section").isGreaterThanOrEqualTo(7);
        for (String block : blocks) {
            ScriptedRun run = new ScriptedRun(dir);
            HarnessExecutor executor = run.executor(block);
            // Any variable the examples name is "set" (to something that looks like a credential or a URL).
            executor.setEnvLookup(GuideExamplesTest::fakeEnvironment);
            try {
                executor.initialize();
            } catch (RuntimeException e) {
                throw new AssertionError("this guide example doesn't load:\n" + block + "\n→ " + e.getMessage(), e);
            }
        }
    }

    static String fakeEnvironment(String name) {
        return switch (name) {
            case "DB_URL" -> "jdbc:h2:mem:guide;DB_CLOSE_DELAY=-1";
            case "SLACK_WEBHOOK", "DISCORD_HOOK", "INGEST_URL" -> "https://hooks.example.com/services/" + name + "-token-1234";
            default -> "value-for-" + name;
        };
    }

    @Test
    @Tag("V10.1")
    void theSectionNamesAllSixKindsAndTheRulesTheyShare() throws Exception {
        String guide = Files.readString(GUIDE, StandardCharsets.UTF_8);
        String section = guide.substring(guide.indexOf("### Generic Tools"), guide.indexOf("### Memory, Voice and Languages"));
        for (String kind : List.of("webhook", "email", "http", "file", "shell", "sql")) {
            assertThat(section).contains("#### " + kind).contains("use: " + kind);
        }
        assertThat(section).contains("on_unknown").contains("Idempotency-Key").contains("allow_private").contains("approve:")
                .contains("unattended: true").contains("read-only").contains("Secrets come from the environment");
    }

    @Test
    @Tag("V10.5")
    @Tag("V10.7")
    void theDocumentedCommandsRunAsTheGuideShowsThem() throws Exception {
        Path samples = Path.of("samples/digest").toAbsolutePath();
        Path store = dir.resolve("triggers");
        Path home = Files.createDirectories(dir.resolve("home"));
        Path envFile = dir.resolve("env");
        Files.writeString(envFile, "GEMINI_API_KEY=k\n");
        Map<String, String> env = Map.of("HN_URL", "http://localhost:9", "GEMINI_API_KEY", "key-for-the-check",
                "SLACK_WEBHOOK", "https://hooks.slack.com/services/T000/B000/XXXXXXXX", "HOME", home.toString());

        Result check = weave(samples, env, "check", "digest.loom");
        assertThat(check.exit).as(check.output).isZero();
        assertThat(check.output).contains("digest.loom: ready to run");
        Result checkSlack = weave(samples, env, "check", "digest-slack.loom");
        assertThat(checkSlack.exit).as(checkSlack.output).isZero();

        Result sync = weave(samples, env, "schedule", "sync", "digest.loom", "--store", store.toString());
        assertThat(sync.exit).as(sync.output).isZero();
        assertThat(sync.output).contains("Morning").contains("0 7 * * *").contains("Asia/Kolkata");

        Result list = weave(samples, env, "triggers", "list", store.toString());
        assertThat(list.exit).isZero();
        assertThat(list.output).contains("Morning");

        // Without --apply the install only shows what it would do: nothing is written.
        Result plan = weave(samples, env, "triggers", "install", store.toString(), "--backend", "systemd", "--env-file", envFile.toString());
        assertThat(plan.exit).as(plan.output).isZero();
        assertThat(plan.output).contains("System trigger (systemd)").contains(".timer").contains("EnvironmentFile=" + envFile);
        assertThat(home.resolve(".config")).doesNotExist();
    }

    // ── A real weave process ────────────────────────────────────────────────────────────────

    record Result(int exit, String output) { }

    private static Result weave(Path workingDir, Map<String, String> env, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "io.github.llm4j.loom.cli.WeaveCLI"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDir.toFile()).redirectErrorStream(true);
        builder.environment().putAll(env);
        Process p = builder.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) out.append(line).append('\n');
        }
        assertThat(p.waitFor(90, TimeUnit.SECONDS)).as("weave " + String.join(" ", args) + " finished").isTrue();
        return new Result(p.exitValue(), out.toString());
    }
}
