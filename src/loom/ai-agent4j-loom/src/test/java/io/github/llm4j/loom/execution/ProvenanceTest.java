package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The tag arithmetic: what a value built from others is, and what may leave. */
class ProvenanceTest {

    static final List<String> POLICIES = List.of("A", "B");

    @Test
    void anUnknownOriginAndAModelAnswerAreBothUnchecked() {
        assertThat(Provenance.UNCHECKED.level()).isEqualTo(Provenance.Level.UNCHECKED);
        assertThat(Provenance.mayLeave(Provenance.UNCHECKED, "text", POLICIES)).isFalse();
    }

    @Test
    void whatNoModelWroteLeavesFreely() {
        assertThat(Provenance.mayLeave(Provenance.NOT_MODEL, "text", POLICIES)).isTrue();
    }

    @Test
    void aValueWithNoTextInItLeavesWhateverItsTag() {
        assertThat(Provenance.mayLeave(Provenance.UNCHECKED, 12, POLICIES)).isTrue();
        assertThat(Provenance.mayLeave(Provenance.UNCHECKED, true, POLICIES)).isTrue();
        assertThat(Provenance.mayLeave(Provenance.UNCHECKED, "", POLICIES)).isTrue();
    }

    @Test
    void checkedLeavesOnlyWhenEveryDeclaredPolicyWasPassed() {
        assertThat(Provenance.mayLeave(Provenance.checked(List.of("A")), "t", POLICIES)).isFalse();
        assertThat(Provenance.mayLeave(Provenance.checked(List.of("A", "B")), "t", POLICIES)).isTrue();
        assertThat(Provenance.missing(Provenance.checked(List.of("A")), POLICIES)).containsExactly("B");
    }

    @Test
    void joinTakesTheWorseLevelAndOnlyThePoliciesBothPassed() {
        Provenance.Tag ab = Provenance.checked(List.of("A", "B"));
        Provenance.Tag a = Provenance.checked(List.of("A"));
        assertThat(Provenance.join(ab, a).checkedAgainst()).containsExactly("A");
        assertThat(Provenance.join(ab, Provenance.UNCHECKED).level()).isEqualTo(Provenance.Level.UNCHECKED);
        assertThat(Provenance.join(Provenance.NOT_MODEL, ab)).isEqualTo(ab);
        assertThat(Provenance.join(ab, Provenance.NOT_MODEL)).isEqualTo(ab);
    }

    @Test
    void onlyTheTextFieldsOfARecordCount() {
        Provenance.Tag tag = Provenance.model(Set.of("summary"));
        assertThat(Provenance.mayLeave(tag, Map.of("amount", 3, "kind", "refund"), POLICIES)).isTrue();
        assertThat(Provenance.mayLeave(tag, Map.of("summary", "x", "amount", 3), POLICIES)).isFalse();
    }

    @Test
    void aModelThatWroteNoTextFieldsMeansNotModel() {
        assertThat(Provenance.model(Set.of())).isEqualTo(Provenance.NOT_MODEL);
    }

    @Test
    void textInsideListsAndNestedRecordsIsFound() {
        assertThat(Provenance.mayLeave(Provenance.UNCHECKED, List.of(Map.of("a", List.of("deep"))), POLICIES)).isFalse();
        assertThat(Provenance.modelWritten(Provenance.UNCHECKED, "x")).isTrue();
        assertThat(Provenance.modelWritten(Provenance.NOT_MODEL, "x")).isFalse();
    }
}
