package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 19 R16: a metamorphic case asserts a relation between two runs, so it needs no ground
 * truth of its own. The mechanical relations need no model: an irrelevant addition and a
 * reordering of what the input says must not change the conclusion, so the derived case keeps the
 * original's expectations and says which case it came from and which relation it tests.
 */
class MetamorphicTest {

    private static EvalScenario of(String id, String input, String contains, String... tags) {
        return new EvalScenario(id, input, contains, null, null, null, null, id, null, List.of(tags), null, null, null);
    }

    @Test
    void anIrrelevantAdditionKeepsTheExpectationsAndTagsThePairAndTheRelation() {
        Metamorphic.Derived d =
                Metamorphic.derive(List.of(of("refund-1", "I was charged twice. Please refund one.", "refund", "domain:billing")), List.of(Metamorphic.Relation.IRRELEVANT));

        assertThat(d.cases()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo("refund-1~irrelevant");
            assertThat(s.input()).startsWith("I was charged twice. Please refund one.").hasSizeGreaterThan("I was charged twice. Please refund one.".length());
            assertThat(s.expectedOutputContains()).isEqualTo("refund");
            assertThat(s.tags()).contains("domain:billing", "relation:irrelevant", "pair:refund-1", "derived:metamorphic");
        });
    }

    @Test
    void aReorderingReversesTheSentencesAndKeepsTheExpectations() {
        Metamorphic.Derived d =
                Metamorphic.derive(List.of(of("a", "First fact. Second fact. Third fact.", "x")), List.of(Metamorphic.Relation.ORDER));

        assertThat(d.cases()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo("a~order");
            assertThat(s.input()).isEqualTo("Third fact. Second fact. First fact.");
            assertThat(s.tags()).contains("relation:order", "pair:a");
        });
    }

    @Test
    void aCaseWithOneSentenceCannotBeReorderedAndIsReportedSkippedNotDropped() {
        Metamorphic.Derived d = Metamorphic.derive(List.of(of("one", "Only one sentence.", "x")), List.of(Metamorphic.Relation.ORDER));

        assertThat(d.cases()).isEmpty();
        assertThat(d.skipped()).singleElement().asString().contains("one").contains("order").contains("one sentence");
    }

    @Test
    void aCaseWithNoInputIsSkippedAndNamed() {
        Metamorphic.Derived d = Metamorphic.derive(List.of(of("none", null, "x")), List.of(Metamorphic.Relation.IRRELEVANT));

        assertThat(d.cases()).isEmpty();
        assertThat(d.skipped()).singleElement().asString().contains("none").contains("no input");
    }

    @Test
    void aCaseThatIsAlreadyDerivedIsNotDerivedAgain() {
        Metamorphic.Derived d = Metamorphic.derive(List.of(of("a~irrelevant", "Text. More.", "x", "derived:metamorphic")), List.of(Metamorphic.Relation.IRRELEVANT));

        assertThat(d.cases()).isEmpty();
    }

    @Test
    void relationsAreReadFromTheirNamesAndAModelDependentOneIsSaidNotToBeBuilt() {
        assertThat(Metamorphic.Relation.parse("irrelevant,order")).containsExactly(Metamorphic.Relation.IRRELEVANT, Metamorphic.Relation.ORDER);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Metamorphic.Relation.parse("paraphrase"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paraphrase")
                .hasMessageContaining("needs a model")
                .hasMessageContaining("irrelevant, order");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Metamorphic.Relation.parse("sideways"))
                .hasMessageContaining("sideways");
    }

    @Test
    void aPairThatPassedAloneAndFailedPerturbedIsFragile() {
        ScenarioResult orig = new ScenarioResult("A", "agent", "f", "a", "a", Status.PASS, List.of(), null, null, "q", "o", List.of(), List.of(), List.of());
        ScenarioResult derived = new ScenarioResult("A", "agent", "f", "a~order", "a~order", Status.FAIL, List.of(), null, null, "q", "o", List.of("relation:order", "pair:a"), List.of(), List.of());
        ScenarioResult fine = new ScenarioResult("A", "agent", "f", "b~order", "b~order", Status.PASS, List.of(), null, null, "q", "o", List.of("relation:order", "pair:b"), List.of(), List.of());
        ScenarioResult origB = new ScenarioResult("A", "agent", "f", "b", "b", Status.PASS, List.of(), null, null, "q", "o", List.of(), List.of(), List.of());

        assertThat(Metamorphic.fragile(List.of(orig, derived, fine, origB)))
                .singleElement().asString().contains("a").contains("order").contains("passes alone and fails");
    }
}
