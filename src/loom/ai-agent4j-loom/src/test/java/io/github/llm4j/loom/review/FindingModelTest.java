package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Fix;
import io.github.llm4j.loom.review.model.FixSafety;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Location;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.model.TextEdit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** R1, R2, R5 of spec 09: the one shape every source of findings becomes. */
class FindingModelTest {

    static Finding finding(String rule, Severity severity) {
        return new Finding(
                rule,
                Lens.RELIABILITY,
                severity,
                Location.at("main.loom", 12),
                "title",
                "why",
                "do this",
                List.of(),
                Optional.empty(),
                List.of(),
                "test");
    }

    @Test
    void aFindingNeedsARuleIdAndAFixHint() {
        assertThatThrownBy(() -> finding("", Severity.LOW)).hasMessageContaining("rule id");
        assertThatThrownBy(
                        () ->
                                new Finding(
                                        "LR01",
                                        Lens.RELIABILITY,
                                        Severity.LOW,
                                        Location.at("f", 1),
                                        "t",
                                        "w",
                                        " ",
                                        List.of(),
                                        Optional.empty(),
                                        List.of(),
                                        "s"))
                .hasMessageContaining("fix hint");
    }

    @Test
    void severityOrdersMostSeriousFirstAndParsesWords() {
        assertThat(Severity.HIGH.atLeast(Severity.MEDIUM)).isTrue();
        assertThat(Severity.LOW.atLeast(Severity.MEDIUM)).isFalse();
        assertThat(Severity.MEDIUM.atLeast(Severity.MEDIUM)).isTrue();
        assertThat(Severity.parse(" High ")).contains(Severity.HIGH);
        assertThat(Severity.parse("critical")).isEmpty();
        assertThat(Severity.parse(null)).isEmpty();
        assertThat(Severity.INFO.word()).isEqualTo("info");
    }

    @Test
    void eightLensesAreOnByDefaultAndFiveAreOptional() {
        // R33 says nine and four; testability is optional until `weave test` exists (OPEN_ITEMS
        // O14)
        long optional = java.util.Arrays.stream(Lens.values()).filter(Lens::optional).count();
        assertThat(optional).isEqualTo(5);
        assertThat(Lens.TESTABILITY.optional()).isTrue();
        assertThat(Lens.values()).hasSize(13);
        assertThat(Lens.parse("Blast_Radius")).contains(Lens.BLAST_RADIUS);
        assertThat(Lens.parse("nonsense")).isEmpty();
        assertThat(Lens.BLAST_RADIUS.id()).isEqualTo("blast-radius");
    }

    @Test
    void aLocationKnowsWhenItIsTheWholeScriptAndCanBeTiedToAGraphNode() {
        assertThat(Location.at("a.loom", 0).wholeScript()).isTrue();
        assertThat(Location.at("a.loom", 5).wholeScript()).isFalse();
        assertThat(Location.at("a.loom", 5).withNode("n3").nodeId()).isEqualTo("n3");
    }

    @Test
    void copiesDoNotShareStateAndRelatedIsNotDuplicated() {
        Finding f = finding("LR01", Severity.MEDIUM);
        Finding louder = f.withSeverity(Severity.HIGH);
        assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(louder.severity()).isEqualTo(Severity.HIGH);
        assertThat(f.relatedTo("LA02").relatedTo("LA02").related()).containsExactly("LA02");
        assertThatThrownBy(() -> f.related().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aFixCarriesItsEditsImmutably() {
        TextEdit e = new TextEdit("main.loom", 3, 1, 3, 1, "x");
        Fix fix = new Fix(FixSafety.SAFE, "label", new java.util.ArrayList<>(List.of(e)));
        assertThat(fix.edits()).containsExactly(e);
        assertThatThrownBy(() -> fix.edits().add(e))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
