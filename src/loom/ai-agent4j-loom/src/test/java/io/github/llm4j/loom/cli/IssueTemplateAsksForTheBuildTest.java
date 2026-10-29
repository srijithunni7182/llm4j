package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** A bug report is only useful if it names the build, so the template asks for it. */
class IssueTemplateAsksForTheBuildTest {
    @Test
    void theBugReportTemplateAsksForTheOutputOfWeaveVersion() throws Exception {
        String template =
                Files.readString(Path.of("../../../.github/ISSUE_TEMPLATE/bug_report.md"));

        assertThat(template).contains("weave --version").contains("commit");
    }
}
