package io.github.llm4j.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.tools.CalculatorTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verification plan L10, the point of the contract: the same agent code, unchanged, gets the same
 * right answer from every available provider and model.
 */
@Tag("live")
class LiveSwitchingTest {

    /** The consumer's code: nothing in it knows which provider it runs on. */
    static String solve(LLMClient anyModel) {
        AgentResult result = ReActAgent.builder().llmClient(anyModel).addTool(new CalculatorTool()).maxIterations(6).build()
                .run("A warehouse has 48 boxes with 37 items each. How many items in total? Use the calculator, then answer with the number.");
        return result.getFinalAnswer();
    }

    @Test
    void l10_oneAgentEveryProvider() {
        List<LiveTargets.Target> targets = LiveTargets.all();
        assumeTrue(!targets.isEmpty(), "no provider credentials set (see LiveTargets)");
        Map<String, String> answers = new LinkedHashMap<>();
        for (LiveTargets.Target t : targets) answers.put(t.toString(), solve(t.client()));
        answers.forEach((who, answer) -> System.out.println("L10 " + who + " → " + answer));
        assertThat(answers).allSatisfy((who, answer) -> assertThat(answer.replace(",", "")).as(who).contains("1776"));
    }
}
