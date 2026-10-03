package io.github.llm4j.hexamind.eval.live;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.eval.assertions.AgentAssertions;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.report.EvalReportExtension;
import io.github.llm4j.hexamind.eval.EvalSupport;
import io.github.llm4j.hexamind.eval.GoldenDataset;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Stage 1: one scenario (alex-02, a fabricated premise) end to end for about a cent. It proves that the
 * keys work, the agent searched, the judge returned a rating, and that the real tokens per agent call
 * are close to what the cost model assumed; if they are not, the plan's numbers must be redone.
 */
@ExtendWith(EvalReportExtension.class)
@org.junit.jupiter.api.Order(1)
class SmokeEvalTest {

    /** The cost model assumes about 650 output tokens per agent call (answer or step plus thinking). */
    static final int MAX_OUTPUT_TOKENS_PER_CALL = 1300;

    @BeforeAll
    static void declare() {
        EvalSupport.declare();
        EvalSupport.GUARD.stage("smoke", 0.10);
    }

    @Test
    void oneScenarioEndToEnd() {
        EvalScenario s = GoldenDataset.agent("alex").stream().filter(x -> x.id().equals("alex-02")).findFirst().orElseThrow();
        String role = EvalSupport.agent("alex").getPersona().getRole();
        String task = EvalSupport.prompts().get("agent_analyze").orElseThrow().render(Map.of("role", role, "problem", s.input()));
        long callsBefore = EvalSupport.GUARD.calls();
        long outBefore = EvalSupport.GUARD.tokensOut();

        AgentResult r = EvalSupport.run("alex", s, "agent_analyze:v1", task);
        AgentAssertions.assertThat(r).completedSuccessfully().usesTool("WebSearch");
        EvalSupport.rubric("Rubric adherence", s, r, true, EvalSupport.judgeCache(), EvalSupport.JUDGE_ID, 1).matches(r);

        long agentCalls = Math.max(1, r.getUsage() == null ? r.getIterations() : r.getUsage().getLlmCalls());
        double perCall = (EvalSupport.GUARD.tokensOut() - outBefore) / (double) Math.max(1, EvalSupport.GUARD.calls() - callsBefore);
        System.out.printf(
                "SMOKE agent calls=%d, output tokens per model call (agent and judge) ~%.0f, spent $%.4f%n",
                agentCalls, perCall, EvalSupport.GUARD.spentUsd());
        assertThat(perCall).as("output tokens per call").isLessThan(MAX_OUTPUT_TOKENS_PER_CALL);
    }
}
