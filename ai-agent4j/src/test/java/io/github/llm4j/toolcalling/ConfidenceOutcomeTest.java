package io.github.llm4j.toolcalling;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.toolcalling.Scripted.Recorder;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TCC-01: confidence follows what happened to each step, not what its text says. */
class ConfidenceOutcomeTest {

    static final String CALL = "```json\n{\"plan\":\"p\",\"action\":\"calc\",\"action_input\":{\"n\":%d}}\n```";
    static final String DONE = "```json\n{\"plan\":\"p\",\"final_answer\":\"ok\"}\n```";

    private AgentResult text(Recorder tool, String... replies) {
        return ReActAgent.builder().llmClient(Scripted.textModel(replies)).addTool(tool).maxIterations(8).build().run("q");
    }

    @Test
    void aToolThatReturnsTextBeginningWithErrorIsNotPenalised() {
        AgentResult r = text(new Recorder("calc", "Error: no such order, ask the customer"), String.format(CALL, 1), DONE);
        assertEquals(AgentResult.StepOutcome.EXECUTED, r.getSteps().get(0).getOutcome());
        assertEquals(0.7, r.getConfidence().getScore(), 1e-9, r.getConfidence().getReasoning());
    }

    @Test
    void aToolThatThrowsIsPenalisedPerFailure() {
        Recorder boom = new Recorder("calc", "x") {
            @Override public String execute(Map<String, Object> args) { throw new IllegalStateException("kaboom"); }
        };
        AgentResult r = text(boom, String.format(CALL, 1), String.format(CALL, 2), DONE);
        assertEquals(2, r.getSteps().stream().filter(s -> s.getOutcome() == AgentResult.StepOutcome.EXECUTION_ERROR).count());
        assertEquals(0.7 - 2 * 0.15, r.getConfidence().getScore(), 1e-9);
        assertTrue(r.getConfidence().getReasoning().contains("2 tool failure"));
    }

    @Test
    void anUnknownToolAndABlockedDuplicateCountAsFailures() {
        AgentResult unknown = text(new Recorder("calc", "4"), "```json\n{\"plan\":\"p\",\"action\":\"nope\",\"action_input\":{}}\n```", DONE);
        assertEquals(AgentResult.StepOutcome.UNKNOWN_TOOL, unknown.getSteps().get(0).getOutcome());
        assertEquals(0.7 - 0.15, unknown.getConfidence().getScore(), 1e-9);

        AgentResult duplicate = text(new Recorder("calc", "4"), String.format(CALL, 1), String.format(CALL, 1), DONE);
        assertEquals(AgentResult.StepOutcome.DUPLICATE_BLOCKED, duplicate.getSteps().get(1).getOutcome());
        assertEquals(0.7 - 0.15, duplicate.getConfidence().getScore(), 1e-9);
    }

    @Test
    void aHumanRejectionIsADecisionNotAFailure() {
        Recorder calc = new Recorder("calc", "4");
        calc.needsApproval = true;
        AgentResult r = ReActAgent.builder().llmClient(Scripted.textModel(String.format(CALL, 1), DONE)).addTool(calc)
                .approvalCallback((t, a, th) -> false).build().run("q");
        assertEquals(AgentResult.StepOutcome.REJECTED_BY_HUMAN, r.getSteps().get(0).getOutcome());
        assertEquals(0.7, r.getConfidence().getScore(), 1e-9);
    }

    @Test
    void theNativeLoopScoresTheSameWay() {
        Recorder okError = new Recorder("calc", "Error: legitimately reported");
        Scripted model = Scripted.nativeModel(Scripted.calls("", Scripted.call("c1", "calc", Map.of("n", 1))), Scripted.text("ok"));
        AgentResult r = ReActAgent.builder().llmClient(model).addTool(okError).build().run("q");
        assertEquals(0.7, r.getConfidence().getScore(), 1e-9);

        Recorder boom = new Recorder("calc", "x") {
            @Override public String execute(Map<String, Object> args) { throw new IllegalStateException("kaboom"); }
        };
        Scripted failing = Scripted.nativeModel(Scripted.calls("", Scripted.call("c1", "calc", Map.of("n", 1))), Scripted.text("ok"));
        AgentResult f = ReActAgent.builder().llmClient(failing).addTool(boom).build().run("q");
        assertEquals(0.7 - 0.15, f.getConfidence().getScore(), 1e-9);
    }

    @Test
    void theOtherHeuristicsAreUnchanged() {
        assertEquals(0.1, text(new Recorder("calc", "4"), "```json\n{\"plan\":\"p\",\"final_answer\":\"I'm not sure about that\"}\n```").getConfidence().getScore(), 1e-9);
    }
}
