package io.github.llm4j.eval.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** R1.4 and R2 of loom-weave-eval: first-class rubric and expect, typed tags, and the older conventions still read. */
class EvalScenarioFieldsTest {

    private static List<EvalScenario> read(String yaml) {
        return EvalScenarios.fromYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))).stream().map(EvalScenarios::normalized).toList();
    }

    @Test
    void fromYamlLeavesContextExactlyAsWrittenSoOlderCodeKeepsWorking() {
        EvalScenario s = EvalScenarios.fromYaml(new ByteArrayInputStream("- name: x\n  input: y\n  context: [\"RUBRIC: kept\"]\n".getBytes(StandardCharsets.UTF_8))).get(0);

        assertThat(s.context()).containsExactly("RUBRIC: kept");
        assertThat(s.rubric()).isNull();
    }

    @Test
    void r2_1_rubricAndExpectAreFields() {
        EvalScenario s = read("""
                - name: refund
                  input: "I was charged twice"
                  rubric:
                    - Asks a person to approve before refunding
                  expect:
                    - Approval step ran before the refund step
                """).get(0);

        assertThat(s.rubric()).containsExactly("Asks a person to approve before refunding");
        assertThat(s.expect()).containsExactly("Approval step ran before the refund step");
        assertThat(s.rubricLines()).isEqualTo(s.rubric());
    }

    @Test
    void r1_4_snakeCaseAndCamelCaseNamesBothWork() {
        List<EvalScenario> both = read("""
                - name: snake
                  input: x
                  expected_output_contains: "36"
                  expected_tools: [calculator]
                  retrieval_context: [doc]
                - name: camel
                  input: x
                  expectedOutputContains: "36"
                  expectedTools: [calculator]
                  retrievalContext: [doc]
                """);

        assertThat(both.get(0)).usingRecursiveComparison().ignoringFields("name").isEqualTo(both.get(1));
        assertThat(both.get(0).expectedOutputContains()).isEqualTo("36");
        assertThat(both.get(0).expectedTools()).containsExactly("calculator");
        assertThat(both.get(0).retrievalContext()).containsExactly("doc");
    }

    @Test
    void r2_2_theOlderConventionInContextIsReadAsTheFields() {
        EvalScenario s = read("""
                - name: old
                  input: x
                  context:
                    - "background line"
                    - "RUBRIC: Cites a source"
                    - "EXPECT: Ran the search step"
                    - "RUBRIC:Does not guess"
                """).get(0);

        assertThat(s.rubric()).containsExactly("Cites a source", "Does not guess");
        assertThat(s.expect()).containsExactly("Ran the search step");
        assertThat(s.context()).containsExactly("background line");
    }

    @Test
    void r2_2_fieldsComeFirstAndALonelyConventionLineLeavesNoEmptyContext() {
        EvalScenario s = read("""
                - name: both
                  input: x
                  rubric: ["From the field"]
                  context: ["RUBRIC: From the line"]
                """).get(0);

        assertThat(s.rubric()).containsExactly("From the field", "From the line");
        assertThat(s.context()).isNull();
    }

    @Test
    void aScenarioWithNeitherIsUntouched() {
        EvalScenario s = read("- name: plain\n  input: x\n  context: [a, b]\n").get(0);

        assertThat(s.rubric()).isNull();
        assertThat(s.expect()).isNull();
        assertThat(s.rubricLines()).isEmpty();
        assertThat(s.context()).containsExactly("a", "b");
    }

    @Test
    void r2_1_typedTagsReadFromAListOrAMappingAndGiveTheSameThing() {
        List<EvalScenario> both = read("""
                - name: list
                  input: x
                  tags: ["kind:fabricated-premise", "risky"]
                - name: map
                  input: x
                  tags: { kind: fabricated-premise }
                """);

        assertThat(both.get(0).meta()).containsExactly(java.util.Map.entry("kind", "fabricated-premise"));
        assertThat(both.get(1).meta()).isEqualTo(both.get(0).meta());
        assertThat(both.get(1).tags()).containsExactly("kind:fabricated-premise");
        assertThat(both.get(0).tags()).containsExactly("kind:fabricated-premise", "risky");
        assertThat(both.get(0).meta("kind")).isEqualTo("fabricated-premise");
        assertThat(both.get(0).meta("risky")).isNull();
    }

    @Test
    void aTagValueMayContainAColon() {
        assertThat(read("- name: t\n  input: x\n  tags: [\"url:http://example.com\"]\n").get(0).meta("url")).isEqualTo("http://example.com");
    }

    @Test
    void theNewFieldsSurviveAWriteAndRead() {
        EvalScenario s = new EvalScenario("n", "in", null, null, null, null, null, "id-1", List.of("safety"), List.of("kind:x"),
                List.of("Be careful"), List.of("Ran the approval step"));

        EvalScenario back = read(EvalScenarios.toYaml(List.of(s))).get(0);

        assertThat(back).isEqualTo(s);
    }

    @Test
    void theTenFieldFormStillBuildsAScenarioWithNoRubric() {
        EvalScenario s = new EvalScenario("n", "in", null, null, null, null, null, null, null, null);

        assertThat(s.rubric()).isNull();
        assertThat(s.expect()).isNull();
    }
}
