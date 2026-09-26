package io.github.llm4j.eval.dataset;

import java.util.List;

/**
 * One row of a golden dataset — an eval case authored as data (typically YAML, via {@link
 * EvalScenarios}) rather than hardcoded in a test method. This is deliberately a plain data
 * carrier: eval4j does not run assertions on its behalf. A test still writes its own {@code
 * assertThat(...)} chain against each scenario, e.g.:
 *
 * <pre>{@code
 * @ParameterizedTest(name = "{0}")
 * @MethodSource("scenarios")
 * void agentHandlesGoldenScenarios(EvalScenario scenario) {
 *     AgentResult result = agent.run(scenario.input());
 *     assertThat(result).hasFinalAnswerContaining(scenario.expectedOutputContains());
 * }
 *
 * static Stream<EvalScenario> scenarios() {
 *     return EvalScenarios.fromYamlResource("scenarios.yaml").stream();
 * }
 * }</pre>
 *
 * <p>Every field besides {@code input} is optional and may be {@code null} — a test decides which
 * fields a given dataset actually needs to check.
 */
public record EvalScenario(
        String name,
        String input,
        String expectedOutputContains,
        String expectedOutput,
        List<String> expectedTools,
        List<String> context,
        List<String> retrievalContext) {

    /**
     * Used as the parameterized-test display name, so reports show {@code name} rather than the
     * full record dump.
     */
    @Override
    public String toString() {
        return name != null ? name : input;
    }
}
