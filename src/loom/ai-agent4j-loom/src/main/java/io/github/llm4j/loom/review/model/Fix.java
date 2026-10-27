package io.github.llm4j.loom.review.model;

import java.util.List;

/**
 * A machine-applicable change for a finding: ordered, non-overlapping edits over one or more files.
 */
public record Fix(FixSafety safety, String label, List<TextEdit> edits) {
    public Fix {
        edits = List.copyOf(edits);
    }
}
