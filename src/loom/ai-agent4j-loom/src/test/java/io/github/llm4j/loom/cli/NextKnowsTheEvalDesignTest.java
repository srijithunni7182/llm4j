package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 23 R8: {@code weave next} offers the evaluation design when a project has agents and no
 * {@code EVAL_DESIGN.md}, says so once, and stops when the design exists or evaluation was
 * skipped on purpose.
 */
class NextKnowsTheEvalDesignTest {

    @TempDir Path dir;

    private String next(Path project) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out, true));
        try {
            WeaveCLI.commandLine().execute("next", project.toString());
            return out.toString();
        } finally {
            System.setOut(old);
        }
    }

    private Path project() {
        Path p = dir.resolve("p");
        assertThat(WeaveCLI.commandLine().execute("init", "pipeline", p.toString(), "--flat")).isZero();
        return p;
    }

    @Test
    void aProjectWithAgentsAndNoDesignIsOfferedTheEvaluationDesignSkillOnce() {
        String text = next(project());

        assertThat(text).contains("Design the evaluation").contains("llm4j-eval-design").contains("EVAL_DESIGN.md");
        assertThat(text.split("Design the evaluation", -1).length - 1).isEqualTo(1);
    }

    @Test
    void aDesignThatExistsIsNotOfferedAgain() throws Exception {
        Path p = project();
        Files.writeString(p.resolve("EVAL_DESIGN.md"), "# design\n");

        assertThat(next(p)).doesNotContain("Design the evaluation");
    }

    @Test
    void evaluationSkippedOnPurposeIsNotNaggedAbout() throws Exception {
        Path p = project();
        Files.writeString(p.resolve("README.md"), Files.readString(p.resolve("README.md")) + "\nEvaluation: skipped\n");

        assertThat(next(p)).doesNotContain("Design the evaluation");
    }

    @Test
    void aProjectWithNoDatasetIsToldToDesignBeforeBuildingOneAndOneWithADatasetToDesignOrReview()
            throws Exception {
        Path p = project();
        String withDataset = next(p);
        assertThat(withDataset).contains("or have the one you have reviewed").contains("EVAL_REVIEW.md");

        try (var tree = Files.walk(p.resolve("eval"))) {
            for (Path f : tree.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(f);
        }
        assertThat(next(p)).contains("Design the evaluation before building the dataset around it");
    }
}
