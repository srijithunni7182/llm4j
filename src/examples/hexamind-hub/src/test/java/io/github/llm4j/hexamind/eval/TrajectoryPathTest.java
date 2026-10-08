package io.github.llm4j.hexamind.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.export.WorkflowTrace;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Runs hexamind.loom on a scripted model and checks which way the debate went. Free and offline. */
class TrajectoryPathTest {

    private static final String FABRICATED = "{\"fabricated\": \"YES\", \"term\": \"Quantum Flux\"}";
    private static final String REAL = "{\"fabricated\": \"NO\", \"term\": \"\"}";

    private static LoomDebateRunner.Debate collaborate(ScriptedModel model, String problem) {
        return LoomDebateRunner.run(
                "Collaborate", Map.of("problem", problem), "final_text", List.of(), List.of("Quantum Flux"), model);
    }

    @Test
    void fabricatedPremiseTakesTheDebunkBranch() {
        ScriptedModel model =
                new ScriptedModel().whenSeen("Round 1 findings", "It does not exist.").whenSeen("Alex: ", FABRICATED);
        var d = collaborate(model, "Explain the Quantum Flux protocol");
        assertThat(d.trace().actualPath()).isEqualTo(LoomDebateRunner.DEBUNK);
        assertThat(d.output()).isEqualTo("It does not exist.");
    }

    @Test
    void realPremiseRunsAllFiveRoundsThenTheCoordinator() {
        ScriptedModel model = new ScriptedModel().whenSeen("Alex: ", REAL);
        var d = collaborate(model, "Should we adopt Kubernetes?");
        assertThat(d.trace().actualPath()).isEqualTo(LoomDebateRunner.FULL);
    }

    @Test
    void everyRoundDelegatesToAllSixAgents() {
        ScriptedModel model = new ScriptedModel().whenSeen("Alex: ", REAL);
        var d = collaborate(model, "Should we adopt Kubernetes?");
        for (String a : List.of("Alex", "Jordan", "Sasha", "Aris", "Casey", "Rahul")) {
            assertThat(delegations(d, a)).as(a).isEqualTo(5);
        }
        assertThat(delegations(d, "Moderator")).isEqualTo(1);
        assertThat(delegations(d, "Coordinator")).isEqualTo(1);
        // 6 agents x 5 rounds + moderator + coordinator + the handoff back to the coordinator
        assertThat(model.calls()).isEqualTo(33);
    }

    private static long delegations(LoomDebateRunner.Debate d, String agent) {
        return d.trace().events().stream()
                .filter(e -> "delegate_start".equals(e.type()) && agent.equals(e.agent()))
                .count();
    }

    @Test
    void refinementRunsOneRoundAndRebuildsTheConsensus() {
        ScriptedModel model = new ScriptedModel().whenSeen("Rebuild the consensus", "Revised for older customers.");
        var d =
                LoomDebateRunner.run(
                        "Refine",
                        Map.of("topic", "p", "prior", "old", "feedback_text", "ignores customers over 70"),
                        "revised_text",
                        List.of(),
                        List.of(),
                        model);
        assertThat(d.output()).isEqualTo("Revised for older customers.");
        assertThat(model.calls()).isEqualTo(8); // six agents, the coordinator, and the handoff
    }

    @Test
    void aTightBudgetStopsTheDebateWithoutAConsensus() {
        ScriptedModel model = new ScriptedModel().whenSeen("Alex: ", REAL);
        var d =
                LoomDebateRunner.run(
                        "Collaborate",
                        Map.of("problem", "Should we adopt Kubernetes?"),
                        "final_text",
                        List.of(),
                        List.of(),
                        model,
                        s -> s.replace("tokens: 900000", "tokens: 2000"));
        assertThat(d.stopped()).contains("budget exhausted");
        assertThat(d.output()).isNullOrEmpty();
        assertThat(model.calls()).isLessThan(33);
    }
}
