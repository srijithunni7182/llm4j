package io.github.llm4j.eval.dataset;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p>A workflow with several parameters takes them by name in {@code inputs:} ({@code input} alone is its first parameter or an agent's task).
 * Every field besides {@code input} (or {@code inputs}) is optional and may be {@code null} — a test decides which
 * fields a given dataset actually needs to check.
 */
public record EvalScenario(
        String name,
        @JsonDeserialize(using = InputDeserializer.class) String input,
        @JsonAlias("expected_output_contains") String expectedOutputContains,
        @JsonAlias("expected_output") String expectedOutput,
        @JsonAlias("expected_tools") List<String> expectedTools,
        List<String> context,
        @JsonAlias("retrieval_context") List<String> retrievalContext,
        String id,
        List<String> dimensions,
        @JsonDeserialize(using = TagsDeserializer.class) List<String> tags,
        List<String> rubric,
        List<String> expect,
        @JsonDeserialize(using = InputsDeserializer.class) Map<String, String> inputs,
        @JsonAlias("expected_output_not_contains")
        @com.fasterxml.jackson.annotation.JsonFormat(with = com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<String> expectedOutputNotContains) {

    /** The thirteen-field form, before {@code expected_output_not_contains}. */
    public EvalScenario(
            String name,
            String input,
            String expectedOutputContains,
            String expectedOutput,
            List<String> expectedTools,
            List<String> context,
            List<String> retrievalContext,
            String id,
            List<String> dimensions,
            List<String> tags,
            List<String> rubric,
            List<String> expect,
            Map<String, String> inputs) {
        this(name, input, expectedOutputContains, expectedOutput, expectedTools, context, retrievalContext, id, dimensions, tags, rubric, expect, inputs, null);
    }

    /** What the answer must never contain (any capitalisation): a deterministic check, no judge, never null. */
    public List<String> mustNotContain() {
        return expectedOutputNotContains == null ? List.of() : expectedOutputNotContains;
    }

    /**
     * The twelve-field form, before {@code inputs}: a scenario whose workflow takes one parameter (or an agent's task) is just {@code input}.
     */
    public EvalScenario(
            String name,
            String input,
            String expectedOutputContains,
            String expectedOutput,
            List<String> expectedTools,
            List<String> context,
            List<String> retrievalContext,
            String id,
            List<String> dimensions,
            List<String> tags,
            List<String> rubric,
            List<String> expect) {
        this(name, input, expectedOutputContains, expectedOutput, expectedTools, context, retrievalContext, id, dimensions, tags, rubric, expect, null, null);
    }

    /** The named parameters of a workflow with several, never null; empty when {@code input} alone says everything. */
    public Map<String, String> namedInputs() {
        return inputs == null ? Map.of() : inputs;
    }

    /**
     * Everything but the judged expectations: the ten-field form. {@code rubric} and {@code expect} are what a judge is asked to
     * confirm (about an agent's answer, and about what a workflow run did); they were once written as {@code RUBRIC:} and
     * {@code EXPECT:} lines in {@code context}, which {@link EvalScenarios} still reads as these fields.
     */
    public EvalScenario(
            String name,
            String input,
            String expectedOutputContains,
            String expectedOutput,
            List<String> expectedTools,
            List<String> context,
            List<String> retrievalContext,
            String id,
            List<String> dimensions,
            List<String> tags) {
        this(name, input, expectedOutputContains, expectedOutput, expectedTools, context, retrievalContext, id, dimensions, tags, null, null, null, null);
    }

    /**
     * The typed tags: a tag written {@code kind:fabricated-premise} (or {@code kind: fabricated-premise} as a YAML mapping entry) is
     * the pair {@code kind} and {@code fabricated-premise}. Tags without a colon are not in the map; the order they were written in is kept.
     */
    public Map<String, String> meta() {
        Map<String, String> out = new LinkedHashMap<>();
        if (tags != null) {
            for (String tag : tags) {
                int colon = tag.indexOf(':');
                if (colon > 0) out.putIfAbsent(tag.substring(0, colon).strip(), tag.substring(colon + 1).strip());
            }
        }
        return out;
    }

    /** The value of the typed tag {@code key}, or null. */
    public String meta(String key) {
        return meta().get(key);
    }

    /** The judged expectations about the answer ({@code rubric}), never null. */
    public List<String> rubricLines() {
        return rubric == null ? List.of() : rubric;
    }

    /** The judged expectations about what a workflow run did ({@code expect}), never null. */
    public List<String> expectLines() {
        return expect == null ? List.of() : expect;
    }

    /** The original seven-field form: no stable id, dimensions or tags. */
    public EvalScenario(
            String name,
            String input,
            String expectedOutputContains,
            String expectedOutput,
            List<String> expectedTools,
            List<String> context,
            List<String> retrievalContext) {
        this(
                name,
                input,
                expectedOutputContains,
                expectedOutput,
                expectedTools,
                context,
                retrievalContext,
                null,
                null,
                null);
    }

    /**
     * Used as the parameterized-test display name, so reports show {@code name} rather than the
     * full record dump.
     */
    @Override
    public String toString() {
        return name != null ? name : input != null ? input : String.valueOf(namedInputs());
    }
}
