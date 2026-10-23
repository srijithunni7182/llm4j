package io.github.llm4j.loom.debug;

import io.github.llm4j.loom.runtime.RunJournal;
import java.util.Map;

/** An in-memory journal holding the given entries. */
final class RunJournalOf {

    private RunJournalOf() {}

    static RunJournal of(Map<String, RunJournal.Entry> entries) {
        RunJournal j = RunJournal.inMemory();
        entries.forEach(j::put);
        return j;
    }
}
