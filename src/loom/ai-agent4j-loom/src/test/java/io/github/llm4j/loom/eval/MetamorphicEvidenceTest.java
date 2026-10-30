package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The relations that need the domain's evidence or a reworded text. Monotonicity: taking away the
 * decisive evidence must change the conclusion. Negation: flipping it must too. Paraphrase: the
 * conclusion must not change when the question is reworded. The expert marks the evidence with a
 * tag and a person or a model supplies the paraphrases; no model is called here.
 */
class MetamorphicEvidenceTest {

    private static EvalScenario conclusion(String id, String input, String conclusion, String... tags) {
        return new EvalScenario(id, input, null, null, null, null, null, id, null, List.of(tags), null, null, null, null, null, null, conclusion, conclusion == null ? null : List.of("approve it"), null, null, null, null);
    }

    private static final String INPUT = "The invoice is signed. The amount is under the limit. The vendor is on the list.";

    @Test
    void removingTheDecisiveEvidenceForbidsTheOriginalConclusion() {
        EvalScenario s = conclusion("inv-1", INPUT, "approve", "decisive:The invoice is signed.");

        Metamorphic.Derived d = Metamorphic.derive(List.of(s), List.of(Metamorphic.Relation.MONOTONICITY));

        assertThat(d.cases()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("inv-1~monotonicity");
            assertThat(c.input()).doesNotContain("signed").contains("under the limit");
            assertThat(c.correctConclusion()).isNull();
            assertThat(c.rejectedAnswers()).containsExactlyInAnyOrder("approve", "approve it");
            assertThat(c.tags()).contains("relation:monotonicity", "pair:inv-1", "derived:metamorphic");
        });
        assertThat(AnswerVerdict.of(d.cases().get(0), "I approve this invoice.").kind()).isEqualTo(AnswerVerdict.Kind.REJECTED);
        assertThat(AnswerVerdict.of(d.cases().get(0), "Reject: it is not signed.").kind()).isNotEqualTo(AnswerVerdict.Kind.REJECTED);
    }

    @Test
    void flippingTheEvidenceForbidsTheOriginalConclusionToo() {
        EvalScenario s = conclusion("inv-2", INPUT, "approve", "negate:is signed=>is not signed");

        Metamorphic.Derived d = Metamorphic.derive(List.of(s), List.of(Metamorphic.Relation.NEGATION));

        assertThat(d.cases()).singleElement().satisfies(c -> {
            assertThat(c.input()).contains("The invoice is not signed.");
            assertThat(c.rejectedAnswers()).contains("approve");
            assertThat(c.tags()).contains("relation:negation");
        });
    }

    @Test
    void aCaseWithoutTheMarkOrTheTextOrAConclusionIsSkippedAndNamed() {
        Metamorphic.Derived d =
                Metamorphic.derive(
                        List.of(
                                conclusion("no-mark", INPUT, "approve"),
                                conclusion("not-there", INPUT, "approve", "decisive:the moon is full"),
                                conclusion("no-conclusion", INPUT, null, "decisive:The invoice is signed.")),
                        List.of(Metamorphic.Relation.MONOTONICITY));

        assertThat(d.cases()).isEmpty();
        assertThat(d.skipped()).hasSize(3);
        assertThat(String.join("\n", d.skipped())).contains("no-mark: monotonicity skipped, no decisive:<text> tag").contains("not-there").contains("does not occur in the input").contains("no-conclusion").contains("correct_conclusion");
    }

    @Test
    void aParaphraseSuppliedForTheCaseKeepsItsExpectationsAndIsMarkedSynthetic() {
        EvalScenario s = conclusion("inv-3", INPUT, "approve");

        Metamorphic.Derived d = Metamorphic.derive(List.of(s, conclusion("other", INPUT, "approve")), List.of(Metamorphic.Relation.PARAPHRASE), Map.of("inv-3", "Signed invoice, small amount, listed vendor."));

        assertThat(d.cases()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("inv-3~paraphrase");
            assertThat(c.input()).isEqualTo("Signed invoice, small amount, listed vendor.");
            assertThat(c.correctConclusion()).isEqualTo("approve");
            assertThat(c.tags()).contains("relation:paraphrase", "pair:inv-3", "source:synthetic");
        });
        assertThat(d.skipped()).singleElement().asString().contains("other").contains("no paraphrase");
    }

    @Test
    void aParaphraseRelationWithoutParaphrasesSaysWhatToSupply() {
        Metamorphic.Derived d = Metamorphic.derive(List.of(conclusion("a", INPUT, "approve")), List.of(Metamorphic.Relation.PARAPHRASE));

        assertThat(d.cases()).isEmpty();
        assertThat(d.skipped()).singleElement().asString().contains("no paraphrase");
    }

    @Test
    void aSystemThatKeepsItsConclusionWhenTheEvidenceIsGoneIsNamedInsensitiveNotFragile() {
        ScenarioResult orig = new ScenarioResult("A", "agent", "f", "inv-1", "inv-1", Status.PASS, List.of(), null, null, "q", "o", List.of(), List.of(), List.of());
        ScenarioResult derived = new ScenarioResult("A", "agent", "f", "inv-1~monotonicity", "inv-1~monotonicity", Status.FAIL, List.of(), null, null, "q", "o", List.of("relation:monotonicity", "pair:inv-1"), List.of(), List.of());

        assertThat(Metamorphic.fragile(List.of(orig, derived))).singleElement().asString().contains("inv-1").contains("monotonicity").contains("did not change its conclusion").doesNotContain("passes alone and fails");
    }
}
