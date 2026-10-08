package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** R7.1 of loom-weave-eval: what the docs say about evaluation is there, and the example they point at exists. */
class EvalDocTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void theLoomGuideDescribesTheCommandItsOptionsAndTheOptOut() throws Exception {
        String guide = read("LOOM_GUIDE.md");

        assertThat(guide).contains("### 4. Evaluating a Workflow (`eval`)").contains("--init").contains("--check").contains("--mock").contains("--yes")
                .contains("--max-cost").contains("--judge").contains("--report").contains("fixtures.yaml").contains("dataset.yaml")
                .contains("evaluation is optional").contains("Passed, failed and unjudged are three counts").contains("500,000 tokens").contains("examples/newsletter");
    }

    @Test
    void llmsTxtDescribesEvaluation() throws Exception {
        assertThat(read("../../../llms.txt")).contains("**Evaluation:**").contains("weave eval script.loom").contains("Evaluation is optional");
    }

    @Test
    void theWorkflowGuideAsksFirstAndKeepsTheOrderAndTheReminder() throws Exception {
        String readme = read("../../../docs/guide/README.md");
        assertThat(readme).contains("## Tests first, or skip them").contains("do you want tests first?").contains("Evaluation: skipped").contains("dataset comes before the script");

        assertThat(read("../../../docs/guide/06-build-the-workflow.md")).contains("the dataset comes **before** this script").contains("needs no Java loader");
        assertThat(read("../../../docs/guide/09-go-live.md")).contains("If you skipped evaluation").contains("no evaluation of this workflow exists");
        assertThat(read("../../../docs/guide/02-golden-dataset.md")).contains("Optional, and where it lives").contains("weave eval <script> --init");
    }

    @Test
    void theSkillAsksOnceRecordsTheChoiceAndNeverWritesAJavaLoader() throws Exception {
        String skill = read("../../../.claude/skills/llm4j-workflow-guide/SKILL.md");

        assertThat(skill).contains("Do you want tests first?").contains("Write the dataset before the script").contains("never write a Java loader")
                .contains("Evaluation: skipped").contains("weave eval workflow.loom --init").contains("weave eval workflow.loom --check").contains("weave eval workflow.loom --mock");
    }

    @Test
    void theExampleDatasetIsThere() {
        for (String f : new String[] {"dataset.yaml", "researcher.yaml", "editor.yaml", "workflow.yaml"}) {
            assertThat(Path.of("../../examples/newsletter/eval/golden").resolve(f)).exists();
        }
    }
}
