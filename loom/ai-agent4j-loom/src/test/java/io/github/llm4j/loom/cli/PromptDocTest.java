package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The guide's prompt-file section and its sample are true (R7.3, R10 of loom-prompt-files). */
class PromptDocTest {

    static final Path SAMPLE = Path.of("samples/newsletter/main.loom");

    @Test
    void theGuideDescribesTheSyntaxTheOptionsAndTheSample() throws Exception {
        String guide = Files.readString(Path.of("LOOM_GUIDE.md"));

        assertThat(guide).contains("### Prompt Files").contains("prompt: \"researcher\"").contains("prompt: \"id@v2\"").contains("--prompts <dir>")
                .contains("--prompt researcher@v1").contains("prompts: \"./dir\"").contains("MarkdownFolderPromptRegistry")
                .contains("Loom: Create Prompt File").contains("samples/newsletter");
    }

    @Test
    void llmsTxtMentionsPromptFilesAndTheSampleExists() throws Exception {
        assertThat(Files.readString(Path.of("../../llms.txt"))).contains("**Prompt files:**").contains("--prompt researcher@v1");
        assertThat(SAMPLE).exists();
        assertThat(Path.of("samples/newsletter/prompts/researcher/v2.md")).exists();
    }

    @Test
    void theSampleChecksCleanWithItsPromptFiles() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = CliProbe.check(SAMPLE.toFile(), false, new PrintStream(out, true, StandardCharsets.UTF_8), k -> "key", new AtomicInteger());

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("ready to run").doesNotContain("✗").doesNotContain("⚠");
        assertThat(code).isZero();
    }

    @Test
    void theSamplesGraphShowsThePromptOnEachAgentStep() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = CliProbe.graph(SAMPLE.toFile(), "json", null, new PrintStream(out, true, StandardCharsets.UTF_8));

        String json = out.toString(StandardCharsets.UTF_8);
        assertThat(code).isZero();
        assertThat(json).contains("\"prompt\": \"researcher@v2\"").contains("\"prompt\": \"writer@v1\"").contains("\"prompt\": \"editor@v1\"");
    }

    @Test
    void everyScriptInTheDocsThatNamesAPromptHasItsFile() throws Exception {
        String main = Files.readString(SAMPLE);
        var m = java.util.regex.Pattern.compile("prompt: \"([a-z0-9_-]+)(?:@(v[0-9]+))?\"").matcher(main);
        int found = 0;
        while (m.find()) {
            found++;
            Path dir = SAMPLE.getParent().resolve("prompts");
            assertThat(Files.exists(dir.resolve(m.group(1) + ".md")) || Files.isDirectory(dir.resolve(m.group(1)))).as(m.group(1)).isTrue();
        }
        assertThat(found).isEqualTo(3);
    }
}
