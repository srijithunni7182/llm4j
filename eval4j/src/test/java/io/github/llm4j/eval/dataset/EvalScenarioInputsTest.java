package io.github.llm4j.eval.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A workflow with several parameters takes them by name in {@code inputs:}; {@code input:} stays text, and a wrong shape says what to write. */
class EvalScenarioInputsTest {

    @TempDir Path dir;

    private EvalDataset load(String yaml) throws IOException {
        Files.writeString(dir.resolve("workflow.yaml"), yaml);
        return EvalDataset.load(dir);
    }

    @Test
    void inputsIsAMappingOfNamesToTextAndNumbersAndBooleansAreReadAsWritten() throws IOException {
        EvalDataset d = load("- id: a\n  inputs: { topic: pricing, limit: 3, urgent: true, note: }\n");
        assertThat(d.problems()).isEmpty();
        EvalScenario s = d.all().get(0);
        assertThat(s.namedInputs()).containsExactly(Map.entry("topic", "pricing"), Map.entry("limit", "3"), Map.entry("urgent", "true"), Map.entry("note", ""));
        assertThat(s.input()).isNull();
    }

    @Test
    void aScenarioWithOnlyInputsHasAnInputAndOneWithNeitherDoesNot() throws IOException {
        assertThat(load("- id: a\n  inputs: { topic: x }\n").problems()).isEmpty();
        assertThat(load("- id: a\n  name: n\n").problems()).singleElement().satisfies(p -> assertThat(p.toString()).contains("has no input"));
    }

    @Test
    void aMappingWhereTextIsExpectedIsExplainedNotAParserException() throws IOException {
        assertThat(load("- id: a\n  input: { topic: x }\n").problems()).singleElement().satisfies(p -> {
            assertThat(p.toString()).contains("input: must be text").contains("inputs: { name: value, ... }");
            assertThat(p.toString()).doesNotContain("Cannot deserialize").doesNotContain("JsonToken");
        });
    }

    @Test
    void inputsThatIsNotAMappingOrHoldsAStructureIsExplained() throws IOException {
        assertThat(load("- id: a\n  inputs: linkedin\n").problems()).singleElement().satisfies(p -> assertThat(p.toString()).contains("inputs: must be a mapping"));
        assertThat(load("- id: a\n  inputs: { topic: [a, b] }\n").problems()).singleElement().satisfies(p -> assertThat(p.toString()).contains("inputs.topic: must be text"));
    }

    @Test
    void theOldTwelveFieldConstructorStillWorksAndHasNoNamedInputs() {
        EvalScenario s = new EvalScenario("n", "in", null, null, null, null, null, "id", null, null, null, null);
        assertThat(s.namedInputs()).isEmpty();
        assertThat(s.toString()).isEqualTo("n");
        assertThat(new EvalScenario(null, null, null, null, null, null, null, null, null, null, null, null, Map.of("a", "b")).toString()).isEqualTo("{a=b}");
    }

    @Test
    void writingAScenarioBackKeepsItsInputs() throws IOException {
        EvalDataset d = load("- id: a\n  inputs: { topic: x }\n  rubric: [\"RUBRIC-FREE\"]\n");
        assertThat(d.all().get(0).namedInputs()).containsExactly(Map.entry("topic", "x"));
    }
}
