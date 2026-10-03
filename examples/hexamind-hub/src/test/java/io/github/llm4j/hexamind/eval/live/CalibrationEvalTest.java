package io.github.llm4j.hexamind.eval.live;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.report.EvalReportExtension;
import io.github.llm4j.hexamind.eval.EvalSupport;
import io.github.llm4j.hexamind.eval.GoldenDataset;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Layer 4: how far to trust the judge. Thirty of the reasoning cases (five per persona) are judged three
 * times each at a non-zero temperature, so the report's Judges page can show the judge's own noise. The
 * agent outputs come from the replay cache of stage 2, so this stage pays for the judge only.
 */
@ExtendWith(EvalReportExtension.class)
class CalibrationEvalTest {

    @BeforeAll
    static void declare() {
        EvalSupport.declare();
        EvalSupport.GUARD.stage("calibration", 0.30); // estimate $0.10
    }

    static Stream<EvalScenario> scenarios() {
        return GoldenDataset.AGENTS.stream().flatMap(a -> GoldenDataset.agent(a).stream().limit(5));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void judgeIsConsistentOnTheSameAnswer(EvalScenario s) {
        String agent = GoldenDataset.tag(s, "agent");
        String role = EvalSupport.agent(agent).getPersona().getRole();
        String task =
                EvalSupport.prompts()
                        .get("agent_analyze")
                        .orElseThrow()
                        .render(Map.of("role", role == null ? "" : role, "problem", s.input()));
        AgentResult result = EvalSupport.run(agent, s, "agent_analyze:v1", task);
        EvalSupport.judgeRubric(
                "Rubric adherence (calibration)", s, result, true, EvalSupport.calibrationCache(), EvalSupport.CALIBRATION_JUDGE_ID, 3);
    }
}
