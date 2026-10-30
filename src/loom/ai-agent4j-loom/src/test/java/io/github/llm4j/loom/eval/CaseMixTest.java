package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 19 R15: {@code weave eval --check} says what a dataset is made of: the share of the three
 * case classes nobody builds (warned below 10%), slices too small to read as a rate (warned below
 * 10 cases), and where the cases came from (information, not a rule).
 */
class CaseMixTest {

    private static EvalScenario of(String id, String... tags) {
        return new EvalScenario(id, "input " + id, null, null, null, null, null, id, null, List.of(tags), null, null, null, null, null, null);
    }

    private static List<EvalScenario> many(int n, String... tags) {
        List<EvalScenario> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(of("c" + System.nanoTime() + "-" + i, tags));
        return out;
    }

    private static List<EvalScenario> join(List<EvalScenario>... parts) {
        List<EvalScenario> out = new ArrayList<>();
        for (List<EvalScenario> p : parts) out.addAll(p);
        return out;
    }

    @Test
    void aClassBelowTenPercentIsWarnedAboutAndNamedWithItsTag() {
        List<EvalScenario> cases =
                join(many(9, "class:unanswerable"), many(91, "domain:billing"));

        List<String> notes = CaseMix.notes(cases);

        assertThat(notes).anyMatch(n -> n.contains("obvious-wrong") && n.contains("0 of 100") && n.contains("class:obvious-wrong"));
        assertThat(notes).anyMatch(n -> n.contains("conservative-action") && n.contains("0 of 100"));
        assertThat(notes).anyMatch(n -> n.contains("unanswerable") && n.contains("9 of 100"));
    }

    @Test
    void aClassAtTenPercentOrMoreIsNotWarnedAbout() {
        List<EvalScenario> cases =
                join(
                        many(10, "class:unanswerable"),
                        many(10, "class:obvious-wrong"),
                        many(10, "class:conservative-action"),
                        many(70, "domain:billing"));

        assertThat(CaseMix.notes(cases)).noneMatch(n -> n.contains("of 100"));
    }

    @Test
    void aSliceOfFewerThanTenCasesIsSaidToBeAnAnecdote() {
        List<EvalScenario> cases =
                join(many(30, "difficulty:standard"), many(4, "difficulty:hard"));

        List<String> notes = CaseMix.notes(cases);

        assertThat(notes).anyMatch(n -> n.contains("difficulty:hard") && n.contains("4 cases") && n.contains("anecdote"));
        assertThat(notes).noneMatch(n -> n.contains("difficulty:standard") && n.contains("anecdote"));
    }

    @Test
    void onlyTheReportedSlicesAreCheckedNotEveryTag() {
        List<EvalScenario> cases = join(many(30, "domain:billing"), many(2, "author:assistant"), many(2, "reviewed_by:ana"));

        assertThat(CaseMix.notes(cases)).noneMatch(n -> n.contains("author:") || n.contains("reviewed_by:"));
    }

    @Test
    void theSourceMixIsReportedAsInformationAndNeverAsAWarning() {
        List<EvalScenario> cases =
                join(many(5, "source:production"), many(3, "source:expert"), many(2, "source:synthetic"));

        List<String> notes = CaseMix.notes(cases);

        assertThat(notes).anyMatch(n -> n.contains("source mix") && n.contains("production 50%") && n.contains("expert 30%") && n.contains("synthetic 20%"));
        assertThat(notes).filteredOn(n -> n.contains("source mix")).allMatch(n -> !n.toLowerCase().contains("warn"));
    }

    @Test
    void casesWithNoSourceTagAreCountedAsUnsaidNotDropped() {
        List<EvalScenario> cases = join(many(2, "source:production"), many(2, "domain:billing"));

        assertThat(CaseMix.notes(cases)).anyMatch(n -> n.contains("source mix") && n.contains("not said 50%"));
    }

    @Test
    void anEmptyDatasetHasNothingToSay() {
        assertThat(CaseMix.notes(List.of())).isEmpty();
    }
}
