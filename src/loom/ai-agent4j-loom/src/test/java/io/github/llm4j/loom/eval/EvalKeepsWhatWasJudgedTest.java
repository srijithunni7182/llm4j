package io.github.llm4j.loom.eval;

import static io.github.llm4j.loom.eval.EvalTestSupport.executor;
import static io.github.llm4j.loom.eval.EvalTestSupport.finalAnswer;
import static io.github.llm4j.loom.eval.EvalTestSupport.scenario;
import static io.github.llm4j.loom.eval.EvalTestSupport.with;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.loom.eval.EvalTestSupport.Models;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F25: the report stored no agent outputs, so a person could not see what was judged. A result now
 * keeps the input, the output that was judged, the scenario's tags, and per agent what it used.
 */
class EvalKeepsWhatWasJudgedTest {
    private static final String SCRIPT =
            """
            agent Helper { model: "m-help" system: "You help." }
            agent Checker { model: "m-check" system: "You check." }
            workflow Main(topic) {
                delegate "Research {topic}" to Helper -> findings
                delegate "Check {findings}" to Checker -> verdict
                return verdict
            }
            """;

    private EvalRunner runner(Models models) {
        return new EvalRunner(
                before -> executor(SCRIPT, models, new ToolRegistry(), before),
                null,
                name -> "m-help");
    }

    private static Models answering(String answer) {
        return new Models((system, n) -> finalAnswer(answer));
    }

    @Test
    void anAgentResultKeepsTheInputTheOutputAndWhatTheAgentUsed() {
        ScenarioResult r =
                runner(answering("The answer is 36."))
                        .agent(
                                "helper.yaml",
                                "Helper",
                                with(scenario("n", "What is 15% of 240?"), "36", null, null, null));

        assertThat(r.input()).isEqualTo("What is 15% of 240?");
        assertThat(r.output()).isEqualTo("The answer is 36.");
        assertThat(r.agents()).hasSize(1);
        AgentFacts helper = r.agents().get(0);
        assertThat(helper.agent()).isEqualTo("Helper");
        assertThat(helper.calls()).isEqualTo(1);
        assertThat(helper.promptTokens()).isEqualTo(10);
        assertThat(helper.completionTokens()).isEqualTo(5);
        assertThat(helper.cutAnswers()).isZero();
        assertThat(helper.fallbacks()).isZero();
    }

    @Test
    void aWorkflowResultNamesEveryAgentThatTookPartWithItsOwnNumbers() {
        ScenarioResult r =
                runner(answering("ok"))
                        .workflow(
                                "workflow.yaml",
                                "Main",
                                "topic",
                                with(scenario("w", "tea"), "ok", null, null, null),
                                null);

        assertThat(r.output()).isEqualTo("ok");
        assertThat(r.agents()).extracting(AgentFacts::agent).containsExactlyInAnyOrder("Helper", "Checker");
        assertThat(r.agents()).allSatisfy(a -> assertThat(a.calls()).isEqualTo(1));
    }

    @Test
    void theScenarioTagsAndDimensionsTravelWithTheResult() {
        EvalScenario tagged =
                new EvalScenario(
                        "n", "q", "36", null, null, null, null, "h-1", List.of("accuracy"),
                        List.of("agent:Helper", "split:validation"), null, null);

        ScenarioResult r = runner(answering("36")).agent("f", "Helper", tagged);

        assertThat(r.tags()).containsExactly("agent:Helper", "split:validation");
        assertThat(r.dimensions()).containsExactly("accuracy");
    }

    @Test
    void anEmptyOutputIsKeptAsEmptyAndTheResultIsAnError() {
        ScenarioResult r =
                runner(answering("")).agent("f", "Helper", with(scenario("n", "q"), "x", null, null, null));

        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(r.output()).isEmpty();
        assertThat(r.error()).contains("produced no output");
    }

    @Test
    void anOutputThatContainsWhatItsCaseForbidsIsWithheldFromTheResult() {
        EvalScenario forbids =
                new EvalScenario(
                        "n", "q", null, null, null, null, null, "h-1", null, null, null, null,
                        null, List.of("4111"));

        ScenarioResult r = runner(answering("Your card 4111 is fine.")).agent("f", "Helper", forbids);

        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(r.output()).isEqualTo(EvalRunner.WITHHELD).doesNotContain("4111");
    }

    @Test
    void noFailureDetailRepeatsTheForbiddenTextEither() {
        EvalScenario forbids =
                new EvalScenario(
                        "n", "q", "refund", "Refund issued.", null, null, null, "h-2", null, null,
                        null, null, null, List.of("4111"));

        ScenarioResult r = runner(answering("Your card 4111 is fine.")).agent("f", "Helper", forbids);

        assertThat(r.checks()).extracting(c -> c.kind()).contains("answer contains", "answer is");
        assertThat(r.checks())
                .allSatisfy(c -> assertThat(String.valueOf(c.detail())).doesNotContain("4111"));
        assertThat(r.checks())
                .filteredOn(c -> c.kind().equals("answer is"))
                .allSatisfy(c -> assertThat(c.detail()).contains("withheld"));
    }

    @Test
    void anOutputIsKeptWhenTheForbiddenTextIsAbsent() {
        EvalScenario forbids =
                new EvalScenario(
                        "n", "q", null, null, null, null, null, "h-1", null, null, null, null,
                        null, List.of("4111"));

        ScenarioResult r = runner(answering("All clear.")).agent("f", "Helper", forbids);

        assertThat(r.output()).isEqualTo("All clear.");
    }
}
