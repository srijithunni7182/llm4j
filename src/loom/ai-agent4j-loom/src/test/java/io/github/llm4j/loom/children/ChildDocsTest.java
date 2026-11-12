package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.ScriptValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Phase 33: the guide's section on starting and supervising workflows is true of the code. */
class ChildDocsTest {

    private static final Path GUIDE = Path.of("LOOM_GUIDE.md");
    private static final String HEADING = "### Starting workflows and supervising them";

    private static String section() throws Exception {
        String guide = Files.readString(GUIDE, StandardCharsets.UTF_8);
        int from = guide.indexOf(HEADING);
        assertThat(from).as("the guide has the section").isGreaterThanOrEqualTo(0);
        return guide.substring(from, guide.indexOf("\n### ", from + 10));
    }

    @Test
    void everyLoomBlockInTheSectionParsesAndPassesTheChecksWithNoErrors() throws Exception {
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("```loom\\n(.*?)```", Pattern.DOTALL).matcher(section());
        while (m.find()) blocks.add(m.group(1));
        assertThat(blocks).isNotEmpty();

        for (String block : blocks) {
            var script = StartParseTest.parse(block);
            var problems =
                    new ScriptValidator()
                            .validate(
                                    script,
                                    new ScriptValidator.Context()
                                            .registeredTools(java.util.Set.of())
                                            .baseDir(Path.of(".")));
            assertThat(problems)
                    .as("errors in this guide example:\n" + block)
                    .noneMatch(p -> p.severity() == ScriptValidator.Severity.ERROR);
        }
    }

    @Test
    void theSectionNamesEveryStatementAndTheLimitsItDocuments() throws Exception {
        String text = section();

        assertThat(text)
                .contains("`start W(args)")
                .contains("`wait h timeout D")
                .contains("`cancel h")
                .contains("`steer h Agent")
                .contains("`supervise h")
                .contains("max_children")
                .contains("max_depth")
                .contains("LA28")
                .contains("LA29");
    }

    @Test
    void theDefaultsTheGuideStatesAreTheOnesTheCodeUses() throws Exception {
        String text = section();

        assertThat(text).contains("(default " + io.github.llm4j.loom.execution.ChildLimits.DEFAULT_MAX_CHILDREN + ")");
        assertThat(text).contains("default " + io.github.llm4j.loom.execution.ChildLimits.DEFAULT_MAX_DEPTH + " levels");
        assertThat(text).contains("floor of 30 seconds");
    }
}
