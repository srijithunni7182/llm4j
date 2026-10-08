package io.github.llm4j.hexamind.eval.live;

import io.github.llm4j.eval.assertions.WorkflowAssertions;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.report.EvalReportExtension;
import io.github.llm4j.hexamind.eval.EvalSupport;
import io.github.llm4j.hexamind.eval.GoldenDataset;
import io.github.llm4j.hexamind.eval.LoomDebateRunner;
import io.github.llm4j.hexamind.eval.ScriptedModel;
import io.github.llm4j.loom.execution.LLMClientFactory;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Layer 3, real: three debates on the Loom workflow, one at a time, in the order of their cost: the
 * standard problem, the fabricated premise, and the user's feedback on the first consensus. Each is
 * checked for the path it took (deterministic) and its consensus is judged against the scenario's
 * EXPECT lines. With {@code -Deval.fake=true} a scripted model plays every agent.
 */
@ExtendWith(EvalReportExtension.class)
@org.junit.jupiter.api.Order(4)
class TrajectoryEvalTest {

    static final List<String> REFINE_PATH = List.of("start", "n1", "n2", "end");
    private static String firstConsensus = "";

    @BeforeAll
    static void declare() {
        EvalSupport.declare();
        EvalSupport.GUARD.stage("debates", 3.60); // estimate $2.40
    }

    static Stream<EvalScenario> scenarios() {
        List<EvalScenario> all = GoldenDataset.workflow();
        return Stream.of("flow-01", "flow-02", "flow-03").map(id -> all.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow());
    }

    private static LLMClientFactory models() {
        if (EvalSupport.FAKE) {
            ScriptedModel m =
                    new ScriptedModel()
                            .whenSeen("Round 1 findings", "The term could not be verified; it does not exist.")
                            .whenSeen("Rebuild the consensus", "Revised consensus: older customers get a phone option.")
                            .whenSeen("QLL-7", "{\"fabricated\": \"YES\", \"term\": \"QLL-7\"}")
                            .whenSeen("Alex: ", "{\"fabricated\": \"NO\", \"term\": \"\"}");
            return m;
        }
        return name -> EvalSupport.agentClient();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void debateTakesTheExpectedPath(EvalScenario s) {
        String kind = GoldenDataset.tag(s, "path");
        double before = EvalSupport.GUARD.spentUsd();
        LoomDebateRunner.Debate d;
        List<String> expected;
        if ("refine".equals(kind)) {
            String problem = s.input().split("\\n\\s*\\n")[0].trim();
            String feedback = s.input().substring(s.input().indexOf("FEEDBACK AFTER CONSENSUS:") + 25).trim();
            expected = REFINE_PATH;
            d =
                    LoomDebateRunner.run(
                            "Refine",
                            Map.of("topic", problem, "prior", firstConsensus, "feedback_text", feedback),
                            "revised_text",
                            expected,
                            List.of(),
                            models());
        } else {
            boolean debunk = "debunk".equals(kind);
            expected = debunk ? LoomDebateRunner.DEBUNK : LoomDebateRunner.FULL;
            d =
                    LoomDebateRunner.run(
                            "Collaborate",
                            Map.of("problem", s.input()),
                            "final_text",
                            expected,
                            debunk ? List.of("QLL-7", "Quantum Lattice") : List.of(),
                            models());
            if ("flow-01".equals(s.id())) {
                firstConsensus = d.output();
            }
        }
        System.out.printf("debate %s: $%.2f, path %s%n", s.id(), EvalSupport.GUARD.spentUsd() - before, d.trace().actualPath());

        double spent = EvalSupport.GUARD.spentUsd() - before;
        if ("flow-01".equals(s.id()) && spent > 2.24) {
            EvalSupport.GUARD.stop(String.format("the first debate cost $%.2f, over 2x its $1.12 estimate", spent));
        }
        SoftAssertions soft = new SoftAssertions();
        soft.check(() -> WorkflowAssertions.assertThat(d.trace()).followsExpectedPath());
        soft.check(() -> WorkflowAssertions.assertThat(d.trace()).invokesAgents("Alex", "Rahul"));
        soft.assertThat(d.stopped()).as("stopped by a budget").isNull();
        soft.assertThat(d.output()).as("consensus").isNotBlank();
        if (!EvalSupport.FAKE) {
            // the scripted fake never calls tools; with real models every debate must search, and only through Search
            soft.check(() -> WorkflowAssertions.assertThat(d.trace()).callsOnlyAllowedTools(java.util.Set.of("Search")));
            if (!"refine".equals(kind)) {
                soft.check(() -> WorkflowAssertions.assertThat(d.trace()).usesToolsInOrder("Search"));
            }
        }
        if (!"refine".equals(kind)) {
            for (String agent : List.of("Alex", "Jordan", "Sasha", "Aris", "Casey", "Rahul")) {
                int rounds = "debunk".equals(kind) ? 1 : 5;
                soft.check(() -> WorkflowAssertions.assertThat(d.trace()).delegatesToTimes(agent, rounds));
            }
        }
        if (d.output() != null && !d.output().isBlank()) {
            soft.assertThat((Object) d.output())
                    .is(EvalSupport.rubric("Debate consensus", s, d.output(), false, EvalSupport.judgeCache(), EvalSupport.JUDGE_ID, 1));
        }
        soft.assertAll();
    }
}
