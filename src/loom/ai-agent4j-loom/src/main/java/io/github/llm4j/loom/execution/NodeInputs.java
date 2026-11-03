package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.runtime.ConditionEvaluator;

/**
 * Reads the inputs an internal node (a classifier or an extractor) is handed. A node sees only what
 * its statement names, so this is the one place those values are resolved.
 */
final class NodeInputs {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(
                            com.fasterxml.jackson.databind.SerializationFeature
                                    .ORDER_MAP_ENTRIES_BY_KEYS,
                            true);

    private NodeInputs() {}

    /**
     * The text of one input: a quoted text with its placeholders filled, or the value of a
     * variable. A variable that was never set reads as "" elsewhere in Loom; handing a node "" would
     * fail open, so the step fails instead.
     *
     * @param lead how the failure message starts, such as {@code "what to classify refers to "}
     * @param consequence how it ends after {@code "which has no value, so "}
     */
    static String text(HarnessExecutor executor, RunStmt.Arg in, String lead, String consequence) {
        if (in.kind() == RunStmt.Kind.STRING) return executor.resolve(in.text());
        String head =
                in.text().contains(".")
                        ? in.text().substring(0, in.text().indexOf('.'))
                        : in.text();
        Object value =
                executor.view().getAll().containsKey(head)
                        ? ConditionEvaluator.resolvePath(in.text(), executor.view())
                        : null;
        if (value == null)
            throw new StepFailure(
                    lead + in.text() + ", which has no value, so " + consequence, null);
        return render(value);
    }

    private static String render(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean)
            return String.valueOf(value);
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
