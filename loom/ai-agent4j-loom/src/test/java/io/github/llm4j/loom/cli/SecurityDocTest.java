package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.generic.support.ScriptedRun;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The repository's SECURITY.md shows scripts that really load, and links to files that really exist. */
class SecurityDocTest {

    static final Path DOC = Path.of("../../SECURITY.md");

    @TempDir
    Path dir;

    @Test
    void everyLoomExampleInTheSecurityGuideLoadsAndPassesTheChecks() throws Exception {
        String text = Files.readString(DOC);
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("```loom\\n(.*?)```", Pattern.DOTALL).matcher(text);
        while (m.find()) blocks.add(m.group(1));
        assertThat(blocks).hasSizeGreaterThanOrEqualTo(5);
        for (String block : blocks) {
            Files.createDirectories(dir.resolve("work"));
            ScriptedRun run = new ScriptedRun(dir);
            run.env.put("TEAM_WEBHOOK", "https://hooks.slack.com/services/T000/B000/XXXX");
            run.env.put("DB_URL", "jdbc:h2:mem:sec");
            run.env.put("DB_RO_USER", "reader");
            run.env.put("DB_RO_PASSWORD", "secret");
            try {
                run.executor(block).initialize();
            } catch (RuntimeException e) {
                throw new AssertionError("this SECURITY.md example doesn't load:\n" + block + "\n→ " + e.getMessage(), e);
            }
        }
    }

    @Test
    void everyLocalLinkInTheSecurityGuidePointsAtAFileThatExists() throws Exception {
        String text = Files.readString(DOC);
        Matcher m = Pattern.compile("\\]\\(([^)#:]+)(#[^)]*)?\\)").matcher(text);
        int seen = 0;
        while (m.find()) {
            seen++;
            assertThat(DOC.getParent().resolve(m.group(1))).as(m.group(0)).exists();
        }
        assertThat(seen).isGreaterThan(0);
        assertThat(Files.readString(Path.of("../../README.md"))).contains("SECURITY.md");
    }
}
