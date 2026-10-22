package io.github.llm4j.context;

import io.github.llm4j.model.Message;
import java.util.List;

/** Does nothing. */
final class NoCompaction implements ContextCompactor {

    static final String NAME = "none";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CompactionResult compact(List<Message> messages, CompactionRequest request) {
        return CompactionResult.unchanged(
                NAME, messages, request, CompactionResult.Outcome.SKIPPED, "compaction is off");
    }
}
