package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The llms.txt files point at documents that exist and name the commands that exist. */
class LlmsTxtTest {

    static final Path ROOT = Path.of("../..");
    static final String REPO = "https://github.com/srijithunni7182/llm4j/blob/main/";

    @Test
    void everyLinkInBothLlmsTxtFilesPointsAtAFileInTheRepository() throws Exception {
        for (Path file : new Path[] {ROOT.resolve("llms.txt"), ROOT.resolve("ai-agent4j/llms.txt")}) {
            String text = Files.readString(file);
            assertThat(text).startsWith("# ").contains("\n> ");
            Matcher m = Pattern.compile("\\]\\(([^)#]+)(#[^)]*)?\\)").matcher(text);
            int seen = 0;
            while (m.find()) {
                String target = m.group(1);
                Path local = target.startsWith(REPO) ? ROOT.resolve(target.substring(REPO.length()))
                        : target.startsWith("http") ? null : file.getParent().resolve(target);
                if (local == null) continue;
                seen++;
                assertThat(local).as(file + " links " + target).exists();
            }
            assertThat(seen).as(file.toString()).isGreaterThan(10);
        }
    }

    @Test
    void theCommandsListedAreTheCommandsWeaveHas() throws Exception {
        String text = Files.readString(ROOT.resolve("llms.txt"));
        Matcher m = Pattern.compile("\\*\\*Commands:\\*\\* `weave ([^`]+)`").matcher(text);
        assertThat(m.find()).isTrue();
        var commands = WeaveCLI.commandLine().getSubcommands().keySet();
        for (String name : m.group(1).split("\\s*\\|\\s*")) assertThat(commands).as("weave " + name).contains(name.strip());
        assertThat(m.group(1).split("\\|")).hasSize(commands.size());
    }
}
