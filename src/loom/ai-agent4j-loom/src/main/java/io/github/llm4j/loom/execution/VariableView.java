package io.github.llm4j.loom.execution;

/**
 * A variable as a person looking at a paused run sees it.
 *
 * @param value the value, cut to {@link DecisionPoint#DISPLAY_LIMIT} characters, or {@link
 *     DecisionPoint#MASKED}
 * @param length the full length of the value in characters
 * @param type {@code text}, {@code number}, {@code yes/no}, {@code list}, {@code object} or {@code
 *     empty}
 * @param boundStep the step that last set it, or null when it is an input of the run
 * @param boundBy that statement as a phrase, or null
 */
public record VariableView(
        String name,
        String value,
        int length,
        String type,
        String boundStep,
        int boundLine,
        String boundBy) {}
