package io.github.llm4j.context;

/** What a {@link ContextManager} reports. Both methods are optional to implement. */
public interface ContextEvents {

    /** Before each model call: how full the context is. */
    default void onUsage(ContextUsage usage) {}

    /** After each attempt to compact, including a skip, a failure and the one notice that compaction has stopped for the run. */
    default void onCompaction(CompactionEvent event) {}
}
