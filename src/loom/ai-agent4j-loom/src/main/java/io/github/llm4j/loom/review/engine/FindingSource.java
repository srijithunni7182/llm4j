package io.github.llm4j.loom.review.engine;

import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.settings.ReviewSettings;

/**
 * Something that produces findings from a {@link ReviewInput}: the security audit, the validator, a
 * rule pack, a Java rule. Sources are independent of one another, and a source that throws is
 * reported as a note and never stops the others.
 */
public interface FindingSource {

    /** A short name for messages, such as {@code audit} or {@code pack core@1}. */
    String name();

    /** Reports what it finds to {@code sink}. Must not run a model, a tool or the network. */
    void collect(ReviewInput input, ReviewSettings settings, Sink sink);

    /** Where a source puts what it finds. */
    interface Sink {
        void finding(Finding finding);

        void note(Note note);
    }
}
