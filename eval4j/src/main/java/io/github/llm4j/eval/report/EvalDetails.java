package io.github.llm4j.eval.report;

import java.util.List;

/**
 * The case behind one judged evaluation, shown in the dashboard's drill-down: what was asked, what
 * the system answered, what was expected, and which chunks were retrieved. Every field is optional.
 */
public record EvalDetails(
        String input,
        String actualOutput,
        String expectedOutput,
        List<String> retrievalContext,
        Long durationMs) {

    /** No case information. */
    public static final EvalDetails NONE = new EvalDetails(null, null, null, null, null);

    /**
     * Longest text kept per field; longer values are cut so one huge answer cannot bloat a report.
     */
    static final int MAX_TEXT = 20_000;

    /** At most this many retrieval chunks are kept. */
    static final int MAX_CHUNKS = 50;

    /**
     * A copy with over-long text truncated and an empty context list normalised to {@code null}.
     */
    EvalDetails bounded() {
        List<String> chunks = null;
        if (retrievalContext != null && !retrievalContext.isEmpty()) {
            chunks = retrievalContext.stream().limit(MAX_CHUNKS).map(EvalDetails::cut).toList();
        }
        return new EvalDetails(
                cut(input), cut(actualOutput), cut(expectedOutput), chunks, durationMs);
    }

    private static String cut(String s) {
        if (s == null || s.length() <= MAX_TEXT) {
            return s;
        }
        return s.substring(0, MAX_TEXT) + "… [truncated " + (s.length() - MAX_TEXT) + " chars]";
    }
}
