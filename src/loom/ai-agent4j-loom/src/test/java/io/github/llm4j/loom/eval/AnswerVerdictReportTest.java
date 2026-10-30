package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Phase 19 R10: the report counts the verdicts and warns when too many answers match nothing. */
class AnswerVerdictReportTest {

    private static ScenarioResult verdict(String id, Status status, String detail) {
        return new ScenarioResult("A", "agent", "f", id, id, status, List.of(new ScenarioResult.Check("answer verdict", "in the accepted set", status, detail)), new BigDecimal("0.01"), null, "q", "a", List.of(), List.of(), List.of());
    }

    private static EvalReport.Run run(ScenarioResult... rs) {
        return new EvalReport.Run("main.loom", false, List.of(rs), 0, null, Map.of());
    }

    private static String section(EvalReport.Run run, String id) {
        return EvalSections.of(run).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow().lines().toString();
    }

    private static List<ScenarioResult> many(int exact, int unmatched, int rejected) {
        List<ScenarioResult> out = new ArrayList<>();
        for (int i = 0; i < exact; i++) out.add(verdict("e" + i, Status.PASS, "verdict: exact (x)"));
        for (int i = 0; i < unmatched; i++) out.add(verdict("u" + i, Status.UNJUDGED, "verdict: unmatched: it matches nothing"));
        for (int i = 0; i < rejected; i++) out.add(verdict("r" + i, Status.FAIL, "verdict: rejected (y)"));
        return out;
    }

    @Test
    void theOverallLinesCountEachVerdict() {
        List<ScenarioResult> rs = many(6, 2, 2);

        String overall = section(new EvalReport.Run("m", false, rs, 0, null, Map.of()), "overall");

        assertThat(overall).contains("answer verdicts").contains("6 in the accepted set").contains("2 rejected").contains("2 unmatched");
    }

    @Test
    void anUnmatchedRateAboveATenthIsACaution() {
        String cautions = section(new EvalReport.Run("m", false, many(8, 2, 0), 0, null, Map.of()), "cautions");

        assertThat(cautions).contains("20%").contains("matched nothing").contains("ground truth is incomplete");
    }

    @Test
    void anUnmatchedRateAtOrBelowATenthIsNotACaution() {
        String cautions = section(new EvalReport.Run("m", false, many(9, 1, 0), 0, null, Map.of()), "cautions");

        assertThat(cautions).doesNotContain("ground truth is incomplete");
    }

    @Test
    void aRunWithNoVerdictChecksSaysNothingOfVerdicts() {
        ScenarioResult plain = new ScenarioResult("A", "agent", "f", "p", "p", Status.PASS, List.of(new ScenarioResult.Check("answer contains", "x", Status.PASS, null)), null, null);

        assertThat(section(run(plain), "overall")).doesNotContain("answer verdicts");
    }
}
