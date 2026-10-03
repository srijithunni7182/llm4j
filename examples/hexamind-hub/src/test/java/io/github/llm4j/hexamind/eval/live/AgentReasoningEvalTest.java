package io.github.llm4j.hexamind.eval.live;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.eval.assertions.AgentAssertions;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.report.EvalReportExtension;
import io.github.llm4j.hexamind.eval.EvalSupport;
import io.github.llm4j.hexamind.eval.GoldenDataset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Layer 1: each persona, one scenario at a time, with recorded search. The agent runs the real {@code
 * agent_analyze} prompt; deterministic checks come first (it finished, it used the tools it should),
 * then one judged check against the scenario's rubric and the very snippets the agent was shown.
 */
@ExtendWith(EvalReportExtension.class)
class AgentReasoningEvalTest {

    @BeforeAll
    static void declare() {
        EvalSupport.declare();
        EvalSupport.GUARD.stage("agent reasoning", 3.60); // estimate $2.40, stop at 1.5x
    }

    static Stream<EvalScenario> scenarios() {
        return GoldenDataset.AGENTS.stream().flatMap(a -> GoldenDataset.agent(a).stream());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void behavesLikeItsPersona(EvalScenario s) {
        String agent = GoldenDataset.tag(s, "agent");
        String role = EvalSupport.agent(agent).getPersona().getRole();
        String task =
                EvalSupport.prompts()
                        .get("agent_analyze")
                        .orElseThrow()
                        .render(Map.of("role", role == null ? "" : role, "problem", s.input()));

        AgentResult result = EvalSupport.run(agent, s, "agent_analyze:v1", task);

        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(result.isCompleted()).as("finished").isTrue();
        List<String> tools = s.expectedTools() == null ? List.of() : s.expectedTools();
        for (String t : tools) {
            soft.check(() -> AgentAssertions.assertThat(result).usesTool(t));
        }
        soft.assertThat(result.getFinalAnswer()).as("answer").isNotBlank();
        var v = EvalSupport.judgeRubric("Rubric adherence", s, result, true, EvalSupport.judgeCache(), EvalSupport.JUDGE_ID, 1);
        soft.assertThat(v.score()).as("rubric: " + v.reason()).isGreaterThanOrEqualTo(EvalSupport.JUDGE_THRESHOLD);
        soft.assertAll();
    }
}
