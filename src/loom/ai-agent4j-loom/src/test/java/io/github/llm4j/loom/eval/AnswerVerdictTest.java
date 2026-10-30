package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 19 R10: an answer is exact, acceptable, rejected or matches nothing. Matching is a
 * deterministic phrase test (any capitalisation, punctuation ignored): the entries of a case are
 * short key phrases the answer must contain. What matches nothing is information, not a failure: it
 * may be a correct answer nobody anticipated.
 */
class AnswerVerdictTest {

    private static EvalScenario of(String conclusion, List<String> equivalents, List<String> rejected) {
        return new EvalScenario("n", "q", null, null, null, null, null, "id", null, null, null, null, null, null, null, null, conclusion, equivalents, rejected, null, null, null);
    }

    @Test
    void theCorrectConclusionIsExact() {
        AnswerVerdict v = AnswerVerdict.of(of("one charge is refunded", List.of(), List.of()), "Done: ONE charge is refunded, per policy.");

        assertThat(v.kind()).isEqualTo(AnswerVerdict.Kind.EXACT);
        assertThat(v.matched()).isEqualTo("one charge is refunded");
    }

    @Test
    void anAcceptedEquivalentIsAcceptableAndNamesWhichOne() {
        AnswerVerdict v = AnswerVerdict.of(of("one charge is refunded", List.of("double billing"), List.of()), "This was double billing on the order.");

        assertThat(v.kind()).isEqualTo(AnswerVerdict.Kind.ACCEPTABLE);
        assertThat(v.matched()).isEqualTo("double billing");
    }

    @Test
    void aRejectedAnswerIsRejected() {
        AnswerVerdict v = AnswerVerdict.of(of("one charge is refunded", List.of(), List.of("two separate orders")), "It looks like two separate orders.");

        assertThat(v.kind()).isEqualTo(AnswerVerdict.Kind.REJECTED);
        assertThat(v.matched()).isEqualTo("two separate orders");
    }

    @Test
    void anAnswerThatMatchesNothingIsUnmatchedNotWrong() {
        AnswerVerdict v = AnswerVerdict.of(of("one charge is refunded", List.of("double billing"), List.of("two separate orders")), "Please contact your bank.");

        assertThat(v.kind()).isEqualTo(AnswerVerdict.Kind.UNMATCHED);
        assertThat(v.matched()).isNull();
    }

    @Test
    void exactBeatsAcceptableBeatsRejectedWhenAnAnswerHoldsMoreThanOne() {
        EvalScenario s = of("one charge", List.of("double billing"), List.of("two orders"));

        assertThat(AnswerVerdict.of(s, "one charge, not two orders").kind()).isEqualTo(AnswerVerdict.Kind.EXACT);
        assertThat(AnswerVerdict.of(s, "double billing, not two orders").kind()).isEqualTo(AnswerVerdict.Kind.ACCEPTABLE);
    }

    @Test
    void capitalisationPunctuationAndSpaceDoNotMatter() {
        EvalScenario s = of("Refund: one charge!", List.of(), List.of());

        assertThat(AnswerVerdict.of(s, "refund   one   charge").kind()).isEqualTo(AnswerVerdict.Kind.EXACT);
    }

    @Test
    void aCaseWithNoAnswerSetHasNoVerdict() {
        assertThat(AnswerVerdict.applies(of(null, null, null))).isFalse();
        assertThat(AnswerVerdict.applies(of("x", null, null))).isTrue();
        assertThat(AnswerVerdict.applies(of(null, List.of("x"), null))).isTrue();
        assertThat(AnswerVerdict.applies(of(null, null, List.of("x")))).isTrue();
    }

    @Test
    void aBlankEntryNeverMatches() {
        AnswerVerdict v = AnswerVerdict.of(of("   ", List.of(""), List.of(" ")), "anything at all");

        assertThat(v.kind()).isEqualTo(AnswerVerdict.Kind.UNMATCHED);
    }
}
