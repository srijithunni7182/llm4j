package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LoomScript;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A ladder written once on an agent is the ladder of every decision that agent proposes for, unless the decision says otherwise. */
@Tag("L2")
class AgentLadderDefaultTest {

    static final String LADDERS = """
            measure agrees(c) = c.response.choice == c.proposal.choice
            ladder Cautious {
                rung watch { mode: blind }
                rung suggest { mode: confirm  climb when history.count >= 10 and history.rate(agrees).lower95 >= 0.6 }
                rung act { mode: autonomous  climb when history.count >= 30 and history.rate(agrees).lower95 >= 0.8 }
                climbing needs approval from risk-owner
            }
            ladder Other {
                rung watch { mode: blind }
                rung act { mode: autonomous  climb when history.count >= 50 }
                climbing is automatic
            }
            """;

    static String agent(String extra) {
        return "agent Triager { model: \"ollama/fake\" system: \"s\" output_schema: { choice: string, reasoning: string } " + extra + " }\n";
    }

    static String decision(String name, String extra) {
        return "decision " + name + " { proposed by: Triager choices: approve, reject ask: lead when the agent changes: start over " + extra + " }\n";
    }

    static DecisionDef only(LoomScript s, String name) {
        return s.getDecisions().stream().filter(d -> d.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aDecisionWithNoLadderOfItsOwnTakesTheAgentsLadder() {
        LoomScript s = LadderChecksTest.parse(LADDERS + agent("ladder: Cautious") + decision("Refund", ""));
        DecisionDef d = only(s, "Refund");
        assertThat(d.getLadder()).isEqualTo("Cautious");
        assertThat(d.isLadderFromAgent()).isTrue();
    }

    @Test
    void everyDecisionTheAgentProposesForTakesIt() {
        LoomScript s = LadderChecksTest.parse(LADDERS + agent("ladder: Cautious") + decision("Refund", "") + decision("Credit", ""));
        assertThat(only(s, "Refund").getLadder()).isEqualTo("Cautious");
        assertThat(only(s, "Credit").getLadder()).isEqualTo("Cautious");
    }

    @Test
    void aDecisionsOwnLadderWins() {
        LoomScript s = LadderChecksTest.parse(LADDERS + agent("ladder: Cautious") + decision("Refund", "ladder: Other"));
        assertThat(only(s, "Refund").getLadder()).isEqualTo("Other");
        assertThat(only(s, "Refund").isLadderFromAgent()).isFalse();
    }

    @Test
    void aDecisionsOwnTrustBlockWins() {
        LoomScript s = LadderChecksTest.parse(LADDERS + agent("ladder: Cautious")
                + decision("Refund", "trust { start at watch  never go above suggest  moving up needs approval from: risk-owner }"));
        assertThat(only(s, "Refund").getLadder()).as("its trust block, written as a ladder, and not the agent's").isEqualTo("trust:Refund");
        assertThat(only(s, "Refund").isLadderFromAgent()).isFalse();
        assertThat(LadderChecksTest.errors(LADDERS + agent("ladder: Cautious")
                + decision("Refund", "trust { start at watch  never go above suggest  moving up needs approval from: risk-owner }"))).isEmpty();
    }

    @Test
    void aDecisionOfAnotherAgentIsLeftAlone() {
        LoomScript s = LadderChecksTest.parse(LADDERS + agent("ladder: Cautious")
                + "agent Writer { model: \"ollama/fake\" system: \"s\" output_schema: { choice: string, reasoning: string } }\n"
                + "decision Post { proposed by: Writer choices: yes, no ask: lead }\n");
        assertThat(only(s, "Post").getLadder()).isNotEqualTo("Cautious");
        assertThat(only(s, "Post").isLadderFromAgent()).isFalse();
    }

    @Test
    void theAgentsCeilingAppliesUnlessTheDecisionStatesItsOwn() {
        LoomScript s = LadderChecksTest.parse(LADDERS + agent("ladder: Cautious ceiling: suggest") + decision("Refund", "") + decision("Credit", "ceiling: act"));
        assertThat(only(s, "Refund").getCeilingRung()).isEqualTo("suggest");
        assertThat(only(s, "Credit").getCeilingRung()).isEqualTo("act");
    }

    @Test
    void theAgentsArgumentsAreHanded() {
        String src = "measure agrees(c) = c.response.choice == c.proposal.choice\n"
                + "ladder P(base) { rung watch { mode: blind } rung act { mode: autonomous climb when history.count >= base } climbing is automatic }\n"
                + agent("ladder: P(base: 40)") + decision("Refund", "");
        DecisionDef d = only(LadderChecksTest.parse(src), "Refund");
        assertThat(d.getLadderArgs()).containsKey("base");
        assertThat(LadderChecksTest.errors(src)).isEmpty();
    }

    @Test
    void anAgentWithNoLadderChangesNothing() {
        LoomScript s = LadderChecksTest.parse(LADDERS + agent("") + decision("Refund", ""));
        assertThat(only(s, "Refund").isLadderFromAgent()).isFalse();
        assertThat(only(s, "Refund").getLadder()).isNotEqualTo("Cautious");
    }

    @Test
    void applyingTwiceChangesNothing() {
        LoomScript s = LadderChecksTest.parse(LADDERS + agent("ladder: Cautious") + decision("Refund", ""));
        s.applyAgentLadders();
        s.applyAgentLadders();
        assertThat(only(s, "Refund").getLadder()).isEqualTo("Cautious");
    }

    // ---- what is wrong is said at load ------------------------------------------------------------------------------------

    @Test
    void anUnknownLadderOnAnAgentIsAnErrorWithAGuess() {
        List<String> e = LadderChecksTest.errors(LADDERS + agent("ladder: Cautius") + decision("Refund", ""));
        assertThat(String.join("\n", e)).contains("no ladder named Cautius").contains("Cautious");
    }

    @Test
    void anUnknownCeilingOnAnAgentIsAnError() {
        List<String> e = LadderChecksTest.errors(LADDERS + agent("ladder: Cautious ceiling: acting") + decision("Refund", ""));
        assertThat(String.join("\n", e)).contains("no rung named acting");
    }

    @Test
    void aCeilingWithoutALadderOnAnAgentIsAnError() {
        List<String> e = LadderChecksTest.errors(LADDERS + agent("ceiling: suggest") + decision("Refund", ""));
        assertThat(String.join("\n", e)).contains("ceiling: needs a ladder");
    }

    @Test
    void aDefaultThatAppliesToNothingIsAWarning() {
        List<String> w = LadderChecksTest.warnings(LADDERS + agent("ladder: Cautious"));
        assertThat(String.join("\n", w)).contains("applies to no decision");
    }

    @Test
    void aSecondLadderOnOneAgentIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LadderChecksTest.parse(LADDERS + agent("ladder: Cautious ladder: Other")))
                .hasMessageContaining("only one default ladder");
    }

    @Test
    void theLadderTheAgentBindsIsNotReportedAsUnused() {
        List<String> w = LadderChecksTest.warnings(LADDERS + agent("ladder: Cautious") + decision("Refund", ""));
        assertThat(String.join("\n", w)).doesNotContain("ladder Cautious: no decision uses it");
    }
}
