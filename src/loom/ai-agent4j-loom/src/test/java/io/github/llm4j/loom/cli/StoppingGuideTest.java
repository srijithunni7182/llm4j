package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The guide's "Steering, Stopping and Wrapping Up an Agent" section is checked against the code: every example loads. */
class StoppingGuideTest {

    static final Path GUIDE = Path.of("LOOM_GUIDE.md");
    static final String HEADING = "### Steering, Stopping and Wrapping Up an Agent";

    @TempDir
    Path dir;

    static String section() throws Exception {
        String guide = Files.readString(GUIDE, StandardCharsets.UTF_8);
        int from = guide.indexOf(HEADING);
        assertThat(from).as("the guide has the section").isGreaterThanOrEqualTo(0);
        return guide.substring(from, guide.indexOf("\n### ", from + 10));
    }

    @Test
    void everyLoomBlockInTheSectionLoadsAndPassesTheChecks() throws Exception {
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("```loom\\n(.*?)```", Pattern.DOTALL).matcher(section());
        while (m.find()) blocks.add(m.group(1));
        assertThat(blocks).hasSize(2);
        for (String block : blocks) {
            HarnessExecutor executor = new ScriptedRun(dir).executor(block);
            try {
                executor.initialize();
            } catch (RuntimeException e) {
                throw new AssertionError("this guide example doesn't load:\n" + block + "\n→ " + e.getMessage(), e);
            }
        }
    }

    @Test
    void everyStopFieldAndReasonTheSectionNamesExists() throws Exception {
        String text = section();
        for (String field : io.github.llm4j.loom.ast.InterventionDef.STOP_FIELDS) assertThat(text).contains("_stop." + field);
        for (String reason : io.github.llm4j.loom.ast.InterventionDef.REASONS) assertThat(text).contains(reason);
    }
}
