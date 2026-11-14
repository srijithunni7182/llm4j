package io.github.llm4j.eval.dataset;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import java.util.Map;

/**
 * One step of a scenario that runs in steps (phase 35, D17): its own input and its own
 * expectations, run in order after the step before it, so "create a page, then add a form to it" is
 * one scenario. What a step leaves behind (files in a workspace, a memory) is what the next one
 * finds.
 *
 * <pre>{@code
 * - name: page then form
 *   steps:
 *     - input: "Create a page with a title"
 *       expected_output_contains: "created"
 *     - input: "Add a contact form to it"
 *       expected_output_not_contains: "error"
 * }</pre>
 */
public record EvalStep(
        String name,
        @JsonDeserialize(using = InputDeserializer.class) String input,
        @JsonDeserialize(using = InputsDeserializer.class) Map<String, String> inputs,
        @JsonAlias("expected_output_contains") String expectedOutputContains,
        @JsonAlias("expected_output_not_contains")
                @com.fasterxml.jackson.annotation.JsonFormat(
                        with = com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                List<String> expectedOutputNotContains,
        @JsonDeserialize(using = RubricDeserializer.class) List<String> rubric) {

    /**
     * This step as a scenario of its own, named after the scenario and the step's place in it, for
     * the checks that read a scenario. It has no steps.
     */
    public EvalScenario asScenario(EvalScenario parent, int number) {
        String label = (parent.name() == null ? "scenario" : parent.name()) + " · step " + number + (name == null ? "" : " (" + name + ")");
        return new EvalScenario(
                label, input, expectedOutputContains, null, null, null, null,
                parent.id() == null ? null : parent.id() + "#" + number,
                null, null, rubric, null, inputs, expectedOutputNotContains, null, null);
    }
}
