package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pattern the guide gives for parallel branches that fall back parses and runs as claimed. */
class ParallelFallbackPatternDocsTest {

    private static final String HEADING = "### Parallel branches that fall back";

    @TempDir Path dir;

    private static String pattern() throws Exception {
        String guide = Files.readString(Path.of("LOOM_GUIDE.md"));
        int start = guide.indexOf(HEADING);
        assertThat(start).as("the guide has the section").isGreaterThanOrEqualTo(0);
        int open = guide.indexOf("```loom\n", start) + "```loom\n".length();
        return guide.substring(open, guide.indexOf("```", open));
    }

    @Test
    void aBranchThatFailsSetsItsOwnFlagAndTheOtherBranchStillCounts() throws Exception {
        List<TestRunner.CaseResult> results =
                Wtest.run(
                        dir,
                        pattern(),
                        "script: ../main.loom\nworkflow: Report\ncases:\n"
                                + "  - name: web search fails\n"
                                + "    input: { topic: tides }\n"
                                + "    fail: { WebResearcher: { always: true, category: timeout } }\n"
                                + "    models:\n"
                                + "      DocsResearcher: found in the archive\n"
                                + "      Writer: the report\n"
                                + "    expect:\n"
                                + "      result: the report\n"
                                + "      degraded: true\n"
                                + "      fallbacks: [ WebResearcher ]\n"
                                + "      context: { web_ok: 'no', docs_ok: 'yes', web_notes: nothing found }\n");

        assertThat(results).allSatisfy(r -> assertThat(r.failures()).as(r.name()).isEmpty());
    }
}
