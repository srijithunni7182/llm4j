package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The script on each phase 35 tool page ("From a Loom script") passes {@code weave check}, so a
 * renamed option, action or word cannot leave the page wrong.
 */
class ToolPagesScriptsTest {

    static final Path DOCS = Path.of("../../tools/ai-agent4j-tools/docs");
    static final Pattern SCRIPT =
            Pattern.compile("## From a Loom script[^\\n]*\\s+```text\\n(.*?)```", Pattern.DOTALL);

    @TempDir Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"compute.md", "verify.md", "scan.md", "workspace.md"})
    void theScriptOnThePagePassesTheCheck(String page) throws Exception {
        Matcher m = SCRIPT.matcher(Files.readString(DOCS.resolve(page)));
        assertThat(m.find()).as("a script under \"From a Loom script\" in " + page).isTrue();
        Path script = Files.writeString(dir.resolve("main.loom"), m.group(1));
        Files.createDirectories(dir.resolve("out"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        x -> null,
                        q -> "",
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> "https://hooks.example.com/placeholder");
        int code = WeaveCLI.check(script.toFile(), null, false, env);
        assertThat(code).as(page + ":\n" + out.toString(StandardCharsets.UTF_8)).isZero();
    }
}
