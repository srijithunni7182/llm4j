package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.InterventionDef;
import io.github.llm4j.loom.guide.Guide;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Chapter 15 (steer, stop and supervise) is listed, travels in the jar, says what the language and
 * the library really do, and its Loom blocks check.
 */
class GuideChapter15Test {

    static final Path GUIDE = Path.of("../../../docs/guide");
    static final Path SKILL = Path.of("../../../.claude/skills/llm4j-workflow-guide/SKILL.md");

    private static String chapter(String file) throws Exception {
        return Files.readString(GUIDE.resolve(file));
    }

    @Test
    void chapter15IsListedOnceAndTravelsInsideTheJar() throws Exception {
        assertThat(Guide.find("15")).isPresent();
        assertThat(Guide.find("steer-stop-and-supervise")).isPresent();
        assertThat(Guide.pages().stream().filter(p -> p.name().equals("15")).count()).isEqualTo(1);
        assertThat(Guide.read("guide/15-steer-stop-and-supervise.md"))
                .contains(chapter("15-steer-stop-and-supervise.md"));
    }

    @Test
    void chapter15NamesEveryPossibilityAndEveryReason() throws Exception {
        String c = chapter("15-steer-stop-and-supervise.md");
        for (String must :
                List.of(
                        "steerable",
                        "streaming",
                        "reasoning",
                        "on_stopped",
                        "intervention",
                        "supervise",
                        "looping(",
                        "tool_failing(",
                        "context >",
                        "steps >",
                        "use partial",
                        "skip",
                        "discard",
                        "retry",
                        "suspend",
                        "conclude",
                        "ask",
                        "rewind to",
                        "_stop.reason",
                        "_stop.note",
                        "_stop.changed",
                        "wrap up",
                        "cancel",
                        "--steer-via console",
                        "weave daemon",
                        "LoomRun",
                        "Outcome.rewindTo",
                        "RunHandle",
                        "RunHook",
                        "ToolHook",
                        "DelegateTaskTool",
                        "LA18",
                        "Fabric",
                        "once",
                        "guard",
                        "weave check",
                        "weave audit",
                        "weave graph",
                        "CTK")) {
            assertThat(c).as("chapter 15 mentions " + must).contains(must);
        }
        for (String reason : InterventionDef.REASONS)
            assertThat(c).as("reason " + reason).contains(reason);
        for (String field : InterventionDef.STOP_FIELDS)
            assertThat(c).as("_stop." + field).contains(field);
    }

    @Test
    void theOtherChaptersAndTheSkillPointToIt() throws Exception {
        assertThat(chapter("README.md")).contains("15-steer-stop-and-supervise.md");
        assertThat(chapter("06-build-the-workflow.md"))
                .contains("15-steer-stop-and-supervise.md")
                .contains("shared_prompt");
        assertThat(chapter("10-best-practices.md")).contains("15-steer-stop-and-supervise.md");
        assertThat(chapter("12-java-quick-reference.md"))
                .contains("RunHandle")
                .contains("RunHook")
                .contains("ToolHook")
                .contains("DelegateTaskTool")
                .contains("LoomRun");
        String skill = Files.readString(SKILL);
        assertThat(skill)
                .contains("docs/guide/15-steer-stop-and-supervise.md")
                .contains("weave guide 15")
                .contains("steerable")
                .contains("on_stopped")
                .contains("supervise")
                .contains("shared_prompt")
                .contains("--steer-via console")
                .contains("RunHook");
    }

    private static final Pattern CHECKED =
            Pattern.compile("<!-- weave check -->\\s*```loom\\n(.*?)```", Pattern.DOTALL);

    @Test
    void everyMarkedLoomBlockInChapter15PassesWeaveCheck(@TempDir Path dir) throws Exception {
        List<String> failures = new ArrayList<>();
        int seen = 0;
        Matcher m = CHECKED.matcher(chapter("15-steer-stop-and-supervise.md"));
        while (m.find()) {
            seen++;
            Path script = dir.resolve("block" + seen + ".loom");
            Files.writeString(script, m.group(1));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream old = System.out;
            System.setOut(new PrintStream(out, true));
            int code;
            try {
                code = WeaveCLI.commandLine().execute("check", script.toString(), "--no-env");
            } finally {
                System.setOut(old);
            }
            if (code != 0) failures.add("block " + seen + ":\n" + out);
        }
        assertThat(seen).isEqualTo(3);
        assertThat(failures).isEmpty();
    }
}
