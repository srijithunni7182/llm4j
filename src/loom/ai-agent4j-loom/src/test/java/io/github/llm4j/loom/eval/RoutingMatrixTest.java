package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 19 R18: routing is reported as a confusion matrix, not one accuracy figure, because some
 * mistakes cost far more than others. A case says which agent should handle it with the tag
 * {@code route:<Agent>}; the agents that took part in the run are what it was routed to. A cell the
 * team names as dangerous (an information-gathering request sent to an action-taking agent) gates
 * on its own.
 */
class RoutingMatrixTest {

    private static ScenarioResult routed(String id, String expected, String... took) {
        List<AgentFacts> agents = new ArrayList<>();
        for (String a : took) agents.add(new AgentFacts(a, 1, 1, 1, BigDecimal.ZERO, 0, 0));
        return new ScenarioResult("Main", "workflow", "f", id, id, Status.PASS, List.of(), null, null, "q", "o", List.of("route:" + expected), List.of(), agents);
    }

    @Test
    void theMatrixCountsEachExpectedAgentAgainstTheAgentsThatRan() {
        RoutingMatrix m = RoutingMatrix.of(List.of(routed("a", "Info", "Info"), routed("b", "Info", "Info"), routed("c", "Info", "Action"), routed("d", "Action", "Action")));

        assertThat(m.count("Info", "Info")).isEqualTo(2);
        assertThat(m.count("Info", "Action")).isEqualTo(1);
        assertThat(m.count("Action", "Action")).isEqualTo(1);
        assertThat(m.count("Action", "Info")).isZero();
        assertThat(m.cases()).isEqualTo(4);
    }

    @Test
    void casesWithoutARouteTagAreNotInTheMatrix() {
        ScenarioResult plain = new ScenarioResult("Main", "workflow", "f", "p", "p", Status.PASS, List.of(), null, null);

        assertThat(RoutingMatrix.of(List.of(plain)).cases()).isZero();
    }

    @Test
    void aDangerousCellWithAnyCaseIsNamedAndGatesOnItsOwn() {
        RoutingMatrix m = RoutingMatrix.of(List.of(routed("a", "Info", "Info"), routed("c", "Info", "Action")));

        assertThat(m.dangerous(List.of("Info>Action"))).singleElement().asString().contains("Info").contains("Action").contains("1 case");
        assertThat(m.dangerous(List.of("Action>Info"))).isEmpty();
    }

    @Test
    void aDangerousCellIsWrittenExpectedGreaterThanActual() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RoutingMatrix.parseDanger("InfoAction"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected>Actual");
        assertThat(RoutingMatrix.parseDanger("Info>Action")).containsExactly("Info", "Action");
    }

    @Test
    void theLinesShowTheMatrixAndMarkTheDangerousCells() {
        RoutingMatrix m = RoutingMatrix.of(List.of(routed("a", "Info", "Info"), routed("c", "Info", "Action")));

        String text = String.join("\n", m.lines(List.of("Info>Action")));

        assertThat(text).contains("routing").contains("Info").contains("Action").contains("DANGEROUS");
    }

    @Test
    void aRunWithNoRoutedCasesHasNoLines() {
        assertThat(RoutingMatrix.of(List.of()).lines(List.of())).isEmpty();
    }

    @Test
    void aRunFailsWhenADangerousCellHasACaseEvenWhenEveryCasePassed() {
        EvalReport.Run run = new EvalReport.Run("m", false, List.of(routed("a", "Info", "Info"), routed("c", "Info", "Action")), 0, null, java.util.Map.of(), null, java.util.Map.of("route-danger", "Info>Action"), "weave eval m");

        assertThat(run.count(Status.FAIL)).isZero();
        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(String.join("\n", EvalSections.of(run).stream().flatMap(s -> s.lines().stream()).toList())).contains("a dangerous cell").contains("DANGEROUS");
    }

    @Test
    void aRunWithNoCaseInTheDangerousCellPasses() {
        EvalReport.Run run = new EvalReport.Run("m", false, List.of(routed("a", "Info", "Info")), 0, null, java.util.Map.of(), null, java.util.Map.of("route-danger", "Info>Action"), "weave eval m");

        assertThat(run.exitCode()).isZero();
    }
}
