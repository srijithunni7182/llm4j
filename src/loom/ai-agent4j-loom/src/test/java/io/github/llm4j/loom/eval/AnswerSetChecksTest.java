package io.github.llm4j.loom.eval;

import static io.github.llm4j.loom.eval.EvalTestSupport.executor;
import static io.github.llm4j.loom.eval.EvalTestSupport.finalAnswer;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.loom.eval.EvalTestSupport.Models;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 19 R10 in the runner: the verdict, the citations and the actions a case rules out. */
class AnswerSetChecksTest {

    private static final String SCRIPT =
            "agent Helper { model: \"m-help\" system: \"You help.\" }\nworkflow Main(t) {\n delegate \"{t}\" to Helper -> r\n return r\n}\n";

    private static ScenarioResult run(String answer, boolean mock, EvalScenario s) {
        Models models = new Models((system, n) -> finalAnswer(answer));
        EvalRunner runner = new EvalRunner(before -> executor(SCRIPT, models, new ToolRegistry(), before), null, name -> "m-help", mock);
        return runner.agent("f.yaml", "Helper", s);
    }

    private static EvalScenario set(String conclusion, List<String> equivalents, List<String> rejected, List<String> okActions, List<String> forbidden, List<String> citations) {
        return new EvalScenario("n", "q", null, null, null, null, null, "id", null, null, null, null, null, null, null, null, conclusion, equivalents, rejected, okActions, forbidden, citations);
    }

    @Test
    void anAcceptableAnswerPassesAndSaysWhichEntryMatched() {
        ScenarioResult r = run("This is double billing.", false, set("one charge refunded", List.of("double billing"), List.of("two orders"), null, null, null));

        assertThat(r.status()).isEqualTo(Status.PASS);
        assertThat(r.checks()).singleElement().satisfies(c -> assertThat(c.detail()).contains("verdict: acceptable").contains("double billing"));
    }

    @Test
    void aRejectedAnswerFailsAndSaysSo() {
        ScenarioResult r = run("It was two orders.", false, set("one charge refunded", List.of(), List.of("two orders"), null, null, null));

        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(r.checks().get(0).detail()).contains("verdict: rejected").contains("two orders");
    }

    @Test
    void anAnswerThatMatchesNothingIsUnjudgedAndTellsAPersonToReadIt() {
        ScenarioResult r = run("Ask your bank.", false, set("one charge refunded", List.of("double billing"), List.of("two orders"), null, null, null));

        assertThat(r.status()).isEqualTo(Status.UNJUDGED);
        assertThat(r.checks().get(0).detail()).contains("verdict: unmatched").contains("accepted_equivalents");
    }

    @Test
    void aRequiredCitationMustBeInTheAnswer() {
        EvalScenario s = set(null, null, null, null, null, List.of("refund policy", "section 3.2"));

        ScenarioResult r = run("Per the refund policy, one charge is refunded.", false, s);

        assertThat(r.checks()).hasSize(2);
        assertThat(r.checks().get(0).status()).isEqualTo(Status.PASS);
        assertThat(r.checks().get(1).status()).isEqualTo(Status.FAIL);
        assertThat(r.checks().get(1).detail()).isEqualTo("the answer does not cite it");
    }

    @Test
    void anActionThatMustNeverHappenFailsWhenTheAnswerTakesIt() {
        EvalScenario s = set(null, null, null, null, List.of("refund both charges"), null);

        assertThat(run("I will refund both charges.", false, s).status()).isEqualTo(Status.FAIL);
        assertThat(run("I will refund one charge.", false, s).status()).isEqualTo(Status.PASS);
    }

    @Test
    void aMockRunJudgesNoneOfIt() {
        ScenarioResult r = run("anything", true, set("x", null, null, null, List.of("y"), List.of("z")));

        assertThat(r.checks()).hasSize(3).allSatisfy(c -> assertThat(c.status()).isEqualTo(Status.UNJUDGED));
    }

    @Test
    void aCaseWithoutAnAnswerSetAddsNoChecks() {
        ScenarioResult r = run("anything", false, new EvalScenario("n", "q", null, null, null, null, null, "id", null, null));

        assertThat(r.checks()).singleElement().satisfies(c -> assertThat(c.kind()).isEqualTo("nothing to check"));
    }
}
