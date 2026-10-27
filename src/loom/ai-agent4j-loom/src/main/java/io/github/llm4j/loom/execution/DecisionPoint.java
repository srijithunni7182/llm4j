package io.github.llm4j.loom.execution;

import java.util.List;
import java.util.Map;

/**
 * A place where a run chose a way: a condition taken one way or the other, a loop going round or
 * leaving, a list about to be walked, a classifier's answer, a rewind, a decision, a failure taken
 * over by a handler. It is only built when a listener asked for it (see {@link
 * HarnessExecutor#setDecisionListener}), so a run nobody is watching pays nothing and changes
 * nothing.
 *
 * @param step the step id of the statement ({@code main/s3}), or "" outside a step
 * @param line the 1-based source line of the statement, or 0
 * @param kind {@code alt}, {@code loop}, {@code for each}, {@code classify}, {@code rewind}, {@code
 *     decide}, {@code failure} or {@code guard}
 * @param statement the statement as a short phrase ({@code alt (intent.urgent == "yes")})
 * @param condition the condition text as written, or null when the decision has none
 * @param operands the values the condition was made of, with where each came from
 * @param result the condition's value, or null when there is no condition
 * @param taken what was done: {@code then}, {@code else}, {@code body}, {@code exit}, ...
 * @param notTaken the ways not taken, each with the first statement it would have run
 * @param detail what else is worth knowing about this kind (a loop's reason for leaving, a
 *     classifier's labels and confidence); numbers and names only, never secrets
 */
public record DecisionPoint(
        String step,
        int line,
        String kind,
        String statement,
        String condition,
        List<Operand> operands,
        Boolean result,
        String taken,
        List<String> notTaken,
        Map<String, Object> detail) {

    /** The longest value shown; a longer one is cut and its full length is kept. */
    public static final int DISPLAY_LIMIT = 200;

    /** The value shown for something that must not be shown. */
    public static final String MASKED = "‹masked›";

    /**
     * One thing a condition looked at.
     *
     * @param expr the path as written ({@code intent.urgent})
     * @param value the value, cut to {@link #DISPLAY_LIMIT} characters, or {@link #MASKED}
     * @param length the full length of the value in characters
     * @param boundStep the step that last set the variable, or null when it was an input
     * @param boundLine the line of the statement that set it, or 0
     * @param boundBy that statement as a phrase ({@code intentclassify Intent}), or null
     */
    public record Operand(
            String expr,
            String value,
            int length,
            String boundStep,
            int boundLine,
            String boundBy) {}
}
