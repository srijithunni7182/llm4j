package io.github.llm4j.loom.review.rule;

import io.github.llm4j.loom.review.model.FixSafety;
import java.util.List;

/**
 * A fix written as data: a safety class, a label and a list of edit operations from a closed set
 * ({@code insertAfter}, {@code insertBefore}, {@code replaceText}, {@code deleteLines}, {@code
 * createFile}). It is checked when the rule loads; turning it into edits and verifying them is the
 * fix engine's job.
 */
public record FixTemplate(FixSafety safety, String label, List<Operation> operations) {

    public FixTemplate {
        operations = List.copyOf(operations);
    }

    /** The operation names a template may use. */
    public static final List<String> OPERATIONS =
            List.of("insertAfter", "insertBefore", "replaceText", "deleteLines", "createFile");

    /** The anchors an operation may name, each resolved from the parsed script. */
    public static final List<String> ANCHORS =
            List.of("statementStart", "statementEnd", "blockEnd", "lineOf", "file");

    /** One edit: what to do, where (an anchor) and with what text. */
    public record Operation(String kind, String anchor, String text) {}
}
