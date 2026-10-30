package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * F60: a single run of each case says little: a case that passes half the time is not a pass. With
 * repetitions the report gives pass@1 beside pass^N and consistency, names the unstable cases, and
 * says how finely this run can tell one result from another.
 */
class RepetitionsReportTest {
    private static ScenarioResult run(String id, Status status) {
        return new ScenarioResult("Writer", "agent", "f", id, id, status,
                List.of(new ScenarioResult.Check("rubric", "r", status, "d")), BigDecimal.ZERO, null,
                "q", "o", List.of(), List.of(), List.of());
    }

    /** Case "steady" passes 4 of 4, "flip" passes 2 of 4, "always-bad" fails 4 of 4. */
    private static EvalReport.Run fourRepetitions() {
        List<ScenarioResult> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(run("steady", Status.PASS));
            results.add(run("flip", i % 2 == 0 ? Status.PASS : Status.FAIL));
            results.add(run("always-bad", Status.FAIL));
        }
        return new EvalReport.Run("main.loom", false, results, 0, null, Map.of(), null,
                Map.of("repetitions", "4"), null);
    }

    private static String readFirst(EvalReport.Run run) {
        String text = EvalSections.terminal(run);
        return text.substring(text.indexOf("== Read this first =="), text.indexOf("== Overall =="));
    }

    @Test
    void passAtOneAndPassHatNAndConsistencyAreStatedSideBySide() {
        String first = readFirst(fourRepetitions());

        // first runs: steady passes, flip passes (i=0), always-bad fails: 2 of 3
        assertThat(first).contains("Each case ran 4 times").contains("pass@1 67%");
        // every run passed only for steady
        assertThat(first).contains("pass^4 33%");
        // steady and always-bad never changed; flip did
        assertThat(first).contains("consistency 67%");
    }

    @Test
    void aCaseThatPassesAboutHalfTheTimeIsNamedAsUnstable() {
        String text = EvalSections.terminal(fourRepetitions());
        String cautions = text.substring(text.indexOf("== Cautions =="), text.indexOf("== How to reproduce =="));

        assertThat(cautions).contains("Unstable").contains("flip (2 of 4 passed)").contains("under-specified");
        assertThat(cautions).doesNotContain("steady (").doesNotContain("always-bad (");
    }

    @Test
    void theResolutionOfTheRunIsStatedFromTheNumberOfCases() {
        String first = readFirst(fourRepetitions());

        // 3 cases: the pass rate is known to within a very wide margin
        assertThat(first).contains("3 case").contains("within about ±").contains("below this run's resolution");
    }

    @Test
    void aSingleRunSaysItRanOnceAndStillStatesItsResolution() {
        EvalReport.Run one =
                new EvalReport.Run("main.loom", false, List.of(run("a", Status.PASS), run("b", Status.FAIL)),
                        0, null, Map.of());

        String first = readFirst(one);

        assertThat(first).contains("Each case ran once").doesNotContain("pass^");
        assertThat(first).contains("within about ±");
    }

    @Test
    void aCaseThatFailsEveryTimeOrPassesEveryTimeIsNeverCalledUnstable() {
        List<ScenarioResult> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(run("good", Status.PASS));
            results.add(run("bad", Status.FAIL));
        }
        EvalReport.Run steady = new EvalReport.Run("main.loom", false, results, 0, null, Map.of(), null,
                Map.of("repetitions", "5"), null);

        assertThat(EvalSections.terminal(steady)).doesNotContain("under-specified");
    }
}
