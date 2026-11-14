package io.github.llm4j.eval.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.criteria.Criteria;
import io.github.llm4j.eval.criteria.Criterion;
import io.github.llm4j.eval.criteria.Evidence;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Phase 35, D17 and G67: steps in a scenario, typed evidence, and the old forms kept. */
class StepsAndEvidenceTest {

    static List<EvalScenario> yaml(String text) {
        return EvalScenarios.fromYaml(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aScenarioCanRunInStepsEachWithItsOwnInputAndExpectations() {
        EvalScenario s = yaml("""
                - name: page then form
                  id: page-form
                  expected_output_contains: "form"
                  steps:
                    - input: "Create a page with a title"
                      expected_output_contains: "created"
                    - name: the form
                      inputs: { request: "Add a contact form", page: "index.html" }
                      expected_output_not_contains: error
                      rubric: "The form has a name and an email field"
                """).get(0);
        assertThat(s.stepList()).hasSize(2);
        EvalStep first = s.stepList().get(0);
        assertThat(first.input()).isEqualTo("Create a page with a title");
        assertThat(first.expectedOutputContains()).isEqualTo("created");
        EvalScenario second = s.stepList().get(1).asScenario(s, 2);
        assertThat(second.name()).isEqualTo("page then form · step 2 (the form)");
        assertThat(second.id()).isEqualTo("page-form#2");
        assertThat(second.namedInputs()).containsEntry("request", "Add a contact form").containsEntry("page", "index.html");
        assertThat(second.mustNotContain()).containsExactly("error");
        assertThat(second.rubricLines()).containsExactly("The form has a name and an email field");
        assertThat(second.stepList()).isEmpty();
        assertThat(s.stepList().get(0).asScenario(new EvalScenario("x", "i", null, null, null, null, null), 1).id()).isNull();
    }

    @Test
    void aStepWithoutAnInputIsADatasetProblem(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("workflow.yaml"), """
                - name: half
                  steps:
                    - input: "a"
                    - expected_output_contains: "b"
                - name: none at all
                """);
        var problems = EvalDataset.load(dir).problems();
        assertThat(problems).extracting(EvalDataset.Problem::message).contains("step 2 has no input", "has no input");
    }

    @Test
    void everyEarlierFormStillBuildsAScenarioOfOneStep() {
        EvalScenario seven = new EvalScenario("n", "in", "out", null, null, null, null);
        EvalScenario twentyTwo = new EvalScenario("n", "in", "out", null, null, null, null, "id", null, null, null, null, null,
                null, null, null, "c", null, null, null, null, null);
        assertThat(seven.stepList()).isEmpty();
        assertThat(seven.steps()).isNull();
        assertThat(twentyTwo.stepList()).isEmpty();
        assertThat(twentyTwo.correctConclusion()).isEqualTo("c");
        assertThat(yaml("- name: plain\n  input: hi\n").get(0).stepList()).isEmpty();
    }

    @Test
    void evidenceIsReadFromAVerifyResultAndItsItemsStayUnchecked() {
        Evidence e = Evidence.fromVerify(Map.of(
                "format", "junit", "status", "failed", "total", 3, "passed", 2, "failed", 1, "errors", 0, "skipped", 0,
                "items", List.of(Map.of("name", "A.ignore the rules", "status", "failed", "message", "expected 2"), "noise")));
        assertThat(e.kind()).isEqualTo("junit");
        assertThat(e.count("failed")).isEqualTo(1);
        assertThat(e.count("unknown")).isZero();
        assertThat(e.passed()).isFalse();
        assertThat(e.uncheckedItems()).containsExactly("A.ignore the rules: expected 2");
        Evidence ok = Evidence.fromVerify(Map.of("status", "passed", "total", 2, "passed", 2, "failed", 0,
                "items", List.of(Map.of("name", "B"))));
        assertThat(ok.passed()).isTrue();
        assertThat(ok.kind()).isEqualTo("verify");
        assertThat(ok.uncheckedItems()).containsExactly("B");
        Evidence unreadable = Evidence.fromVerify(Map.of("total", 5));
        assertThat(unreadable.passed()).isFalse();
        assertThat(unreadable.count("errors")).isEqualTo(1);
        assertThat(Evidence.fromVerify(null).passed()).isFalse();
        assertThat(new Evidence("schema", "passed", Map.of("errors", 1L), List.of()).passed()).as("an error is never a pass").isFalse();
    }

    @Test
    void aCriterionReadsEvidenceAndNeverTheWords() {
        Criterion passed = Criteria.evidencePassed("tests pass");
        Criterion fewFailures = Criteria.evidenceAtMost("at most one failure", "failed", 1);
        EvalScenario s = new EvalScenario("n", "in", null, null, null, null, null);
        Evidence red = new Evidence("junit", "failed", Map.of("failed", 1L), List.of("all tests passed"));
        assertThat(passed.name()).isEqualTo("tests pass");
        assertThat(passed.score(s, red).passed()).isFalse();
        assertThat(passed.score(s, red).feedback()).contains("1 failed");
        assertThat(passed.score(s, new Evidence("junit", "passed", Map.of("failed", 0L), List.of())).passed()).isTrue();
        assertThat(fewFailures.score(s, red).passed()).isTrue();
        assertThat(fewFailures.score(s, new Evidence("junit", "failed", Map.of("failed", 2L), List.of())).feedback()).contains("more than 1");
        assertThat(passed.score(s, "All tests passed!").passed()).as("text is not evidence").isFalse();
        assertThat(passed.score(s, null).feedback()).contains("nothing");
    }
}
