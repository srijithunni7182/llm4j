package io.github.llm4j.eval.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F22: a rubric line could not say which quality dimension it belongs to, so every verdict of a
 * scenario was filed under every dimension of the scenario and counted twice. A line can now be
 * written as {@code {text: ..., dimension: grounding}}; a plain string keeps its old meaning.
 */
class RubricLineDimensionTest {
    private static EvalScenario read(String yaml) {
        return EvalScenarios.fromYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                .get(0);
    }

    @Test
    void aLineWrittenAsAnObjectKeepsItsTextAndItsDimension() {
        EvalScenario s =
                read(
                        """
                        - id: a-1
                          input: q
                          dimensions: [grounding, tone]
                          rubric:
                            - Cites its sources
                            - { text: Uses plain English, dimension: tone }
                            - text: Names every source
                              dimension: grounding
                        """);

        assertThat(s.rubricLines())
                .containsExactly("Cites its sources", "Uses plain English", "Names every source");
        assertThat(s.rubricDimension(0)).isNull();
        assertThat(s.rubricDimension(1)).isEqualTo("tone");
        assertThat(s.rubricDimension(2)).isEqualTo("grounding");
    }

    @Test
    void plainStringsBehaveAsTheyAlwaysDidWithNoDimension() {
        EvalScenario s = read("- id: a-1\n  input: q\n  rubric: [One, Two]\n");

        assertThat(s.rubricLines()).containsExactly("One", "Two");
        assertThat(s.rubricDimension(0)).isNull();
        assertThat(s.rubricDimension(1)).isNull();
    }

    @Test
    void aScenarioBuiltInJavaWithAPlainListHasNoLineDimensions() {
        EvalScenario s =
                new EvalScenario("n", "q", null, null, null, null, null, "id", null, null, List.of("A"), null);

        assertThat(s.rubricDimension(0)).isNull();
        assertThat(s.rubricDimension(5)).isNull();
    }

    private static String problemWith(java.nio.file.Path dir, String yaml) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("helper.yaml"), yaml);
        return EvalDataset.load(dir).problems().stream()
                .map(Object::toString)
                .reduce("", (x, y) -> x + "\n" + y);
    }

    @Test
    void anObjectWithoutTextIsAClearErrorForThePersonReadingTheDataset(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        assertThat(problemWith(dir, "- id: a\n  input: q\n  rubric:\n    - { dimension: tone }\n"))
                .contains("helper.yaml")
                .contains("needs its text");
    }

    @Test
    void anUnknownKeyInALineIsAClearErrorForThePersonReadingTheDataset(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        assertThat(problemWith(dir, "- id: a\n  input: q\n  rubric:\n    - { text: x, dimention: tone }\n"))
                .contains("dimention")
                .contains("takes text and dimension");
    }

    @Test
    void theDimensionsSurviveReadingADatasetFolder(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Files.writeString(
                dir.resolve("helper.yaml"),
                "- id: h-1\n  input: q\n  dimensions: [tone]\n  context: [\"RUBRIC: from context\"]\n  rubric:\n    - { text: Plain, dimension: tone }\n");

        EvalScenario s = EvalDataset.load(dir).files().get("helper").get(0);

        assertThat(s.rubricLines()).containsExactly("Plain", "from context");
        assertThat(s.rubricDimension(0)).isEqualTo("tone");
        assertThat(s.rubricDimension(1)).isNull();
    }
}
