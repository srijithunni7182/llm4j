package io.github.llm4j.eval.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 19 R10: a case may state the set of answers it accepts, the ones it rejects, the actions
 * that are fine and the ones that must never happen, and the sources an answer must cite. A single
 * value is a list of one, and a case that says none of this reads as before.
 */
class EvalScenarioAnswerSetTest {

    private static EvalScenario load(String yaml) {
        return EvalScenarios.fromYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))).get(0);
    }

    @Test
    void theAnswerSetIsReadFromSnakeCaseFields() {
        EvalScenario s =
                load(
                        "- id: dup-1\n  input: q\n  correct_conclusion: one charge is refunded\n  accepted_equivalents: [double billing, duplicate transaction]\n  rejected: [two separate orders]\n  acceptable_actions: [issue refund]\n  unacceptable_actions: [refund both, cancel the order]\n  required_citations: [refund policy]\n");

        assertThat(s.correctConclusion()).isEqualTo("one charge is refunded");
        assertThat(s.equivalents()).containsExactly("double billing", "duplicate transaction");
        assertThat(s.rejectedAnswers()).containsExactly("two separate orders");
        assertThat(s.okActions()).containsExactly("issue refund");
        assertThat(s.forbiddenActions()).containsExactly("refund both", "cancel the order");
        assertThat(s.citations()).containsExactly("refund policy");
        assertThat(s.hasAnswerSet()).isTrue();
    }

    @Test
    void aSingleValueIsAListOfOne() {
        EvalScenario s = load("- id: a\n  input: q\n  rejected: wrong\n  required_citations: policy\n");

        assertThat(s.rejectedAnswers()).containsExactly("wrong");
        assertThat(s.citations()).containsExactly("policy");
    }

    @Test
    void aCaseThatSaysNothingOfTheKindHasNoAnswerSetAndEmptyListsNotNulls() {
        EvalScenario s = load("- id: a\n  input: q\n");

        assertThat(s.hasAnswerSet()).isFalse();
        assertThat(s.equivalents()).isEmpty();
        assertThat(s.rejectedAnswers()).isEmpty();
        assertThat(s.okActions()).isEmpty();
        assertThat(s.forbiddenActions()).isEmpty();
        assertThat(s.citations()).isEmpty();
    }

    @Test
    void theSixteenFieldConstructorIsKept() {
        EvalScenario s =
                new EvalScenario("n", "i", null, null, null, null, null, "id", null, List.of("t"), null, null, null, null, 5, 9);

        assertThat(s.expectedMinWords()).isEqualTo(5);
        assertThat(s.hasAnswerSet()).isFalse();
    }
}
