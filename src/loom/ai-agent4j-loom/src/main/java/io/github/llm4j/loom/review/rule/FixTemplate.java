package io.github.llm4j.loom.review.rule;

import io.github.llm4j.loom.review.model.FixSafety;
import java.util.List;

/**
 * A fix written as data: a safety class, a label and a list of edit operations from a closed set
 * ({@code insertAfter}, {@code insertBefore}, {@code replaceText}, {@code deleteLines}, {@code
 * createFile}). {@code reviewWhen} names the situations where a fix that is usually safe needs a
 * person's judgement after all (such as retrying a step that sends mail). It is checked when the
 * rule loads; turning it into edits and verifying them is the fix engine's job.
 */
public record FixTemplate(
        FixSafety safety, String label, List<Operation> operations, Condition reviewWhen) {

    public FixTemplate(FixSafety safety, String label, List<Operation> operations) {
        this(safety, label, operations, null);
    }

    public FixTemplate {
        operations = List.copyOf(operations);
    }

    /** The operation names a template may use. */
    public static final List<String> OPERATIONS =
            List.of("insertAfter", "insertBefore", "replaceText", "deleteLines", "createFile");

    /** The anchors an operation may name, each resolved from the parsed script. */
    public static final List<String> ANCHORS =
            List.of(
                    "statementStart",
                    "statementEnd",
                    "beforeBrace",
                    "blockStart",
                    "blockEnd",
                    "block",
                    "lineEnd",
                    "line",
                    "lineOf",
                    "fileStart",
                    "file");

    /** One edit: what to do, where (an anchor) and with what text. */
    public record Operation(String kind, String anchor, String text, String find) {
        public Operation(String kind, String anchor, String text) {
            this(kind, anchor, text, null);
        }
    }
}
