package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R3 and R6.3 of loom-weave-eval: the three counts are separate, and what a report prints is safe to open. */
class EvalReportTest {

    private static ScenarioResult result(String target, String id, Status status, String error, ScenarioResult.Check... checks) {
        return new ScenarioResult(target, "agent", target.toLowerCase() + ".yaml", id, "name " + id, status, List.of(checks), new BigDecimal("0.0123"), error);
    }

    private static final ScenarioResult PASS = result("A", "a-1", Status.PASS, null, new ScenarioResult.Check("rubric", "Is kind", Status.PASS, "score 0.90: fine"));
    private static final ScenarioResult FAIL = result("B", "b-1", Status.FAIL, null, new ScenarioResult.Check("answer contains", "36", Status.FAIL, "the answer was: 35"));
    private static final ScenarioResult UNJUDGED = result("C", "c-1", Status.UNJUDGED, null, new ScenarioResult.Check("rubric", "Is <b>safe</b>", Status.UNJUDGED, "no judge ran"));

    private static EvalReport.Run run(boolean mock, ScenarioResult... rs) {
        return new EvalReport.Run("main.loom", mock, List.of(rs), 0, null, Map.of("safety", "Does no harm"));
    }

    @Test
    void passedFailedAndUnjudgedAreCountedApartAndUnjudgedIsNeverAPass() {
        EvalReport.Run run = run(false, PASS, FAIL, UNJUDGED, UNJUDGED);

        assertThat(run.count(Status.PASS)).isEqualTo(1);
        assertThat(run.count(Status.FAIL)).isEqualTo(1);
        assertThat(run.count(Status.UNJUDGED)).isEqualTo(2);
        assertThat(EvalReport.summary(run)).startsWith("1 passed, 1 failed, 2 unjudged").contains("it is not a pass").contains("cost $0.0492");
    }

    @Test
    void theExitCodeIsOneForAFailureAndZeroOtherwiseEvenWithUnjudged() {
        assertThat(run(false, PASS, UNJUDGED).exitCode()).isZero();
        assertThat(run(false, PASS, FAIL).exitCode()).isEqualTo(1);
        assertThat(run(false).exitCode()).isZero();
    }

    @Test
    void aMockRunSaysNothingWasSpentAndWhyItJudgedNothing() {
        String summary = EvalReport.summary(run(true, UNJUDGED));

        assertThat(summary).contains("mock run, nothing was spent").contains("a mock run does not judge").doesNotContain("cost $");
    }

    @Test
    void notRunAndTheReasonAreShown() {
        EvalReport.Run run = new EvalReport.Run("main.loom", false, List.of(PASS), 3, "a limit was reached (10 of 10 tokens)", Map.of());

        assertThat(EvalReport.summary(run)).contains("3 not run").contains("Stopped early: a limit was reached (10 of 10 tokens)");
    }

    @Test
    void theDetailNamesWhatDidNotPassAndHidesWhatDid() {
        String detail = EvalReport.detail(run(false, PASS, FAIL, UNJUDGED));

        assertThat(detail).contains("✓ A · a-1").contains("✗ B · b-1").contains("✗ answer contains: 36").contains("the answer was: 35")
                .contains("? C · c-1").contains("? rubric: Is <b>safe</b>").doesNotContain("Is kind");
    }

    @Test
    void anErrorIsShownOnItsScenario() {
        String detail = EvalReport.detail(run(false, result("A", "a-1", Status.FAIL, "model unreachable")));

        assertThat(detail).contains("error: model unreachable");
    }

    @Test
    void theJsonHasTheCountsTheResultsAndTheDimensions() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(EvalReport.json(run(false, PASS, FAIL, UNJUDGED)));

        assertThat(json.get("passed").asInt()).isEqualTo(1);
        assertThat(json.get("failed").asInt()).isEqualTo(1);
        assertThat(json.get("unjudged").asInt()).isEqualTo(1);
        assertThat(json.get("mock").asBoolean()).isFalse();
        assertThat(json.get("dimensions").get("safety").asText()).isEqualTo("Does no harm");
        assertThat(json.get("results")).hasSize(3);
        assertThat(json.get("results").get(1).get("status").asText()).isEqualTo("fail");
        assertThat(json.get("results").get(1).get("checks").get(0).get("detail").asText()).isEqualTo("the answer was: 35");
    }

    @Test
    void theHtmlEscapesEverythingFromTheDatasetAndRunsNothing() {
        ScenarioResult hostile = result("<img src=x onerror=alert(1)>", "\"><script>alert(2)</script>", Status.UNJUDGED, "<script>boom</script>",
                new ScenarioResult.Check("rubric", "<script>alert(3)</script>", Status.UNJUDGED, "x' onmouseover='y"));

        String html = EvalReport.html(run(true, hostile));

        assertThat(html).doesNotContain("<script").doesNotContain("<img").doesNotContain("onmouseover='").doesNotContain("<b>safe</b>").contains("&lt;img src=x onerror=alert(1)&gt;").contains("&lt;script&gt;alert(3)&lt;/script&gt;")
                .contains("&#39;").contains("<h1>Evaluation of main.loom</h1>").contains("Dimensions");
    }

    @Test
    void theHtmlShowsThreeSeparateCounts() {
        String html = EvalReport.html(run(false, PASS, FAIL, UNJUDGED));

        assertThat(html).contains("<b>1</b> passed").contains("<b>1</b> failed").contains("<b>1</b> unjudged");
    }
}
