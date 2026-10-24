package io.github.llm4j.loom.loop;

import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * A past run as the production loop reads it: where it is, what it ran and with what, and the
 * journal it left. Read-only; nothing here can write to the store.
 *
 * @param id the run's name (its folder)
 * @param dir the run folder
 * @param script the script that ran, as the run recorded it
 * @param workflow the workflow that ran
 * @param inputs what it was given
 * @param scriptHash the fingerprint of the script version that ran, or null for an older run
 * @param modified when the run last wrote to its journal
 * @param journal the journal as it was when the snapshot was taken
 */
public record RunRecord(
        String id,
        Path dir,
        Path script,
        String workflow,
        Map<String, String> inputs,
        String scriptHash,
        Instant modified,
        Map<String, RunJournal.Entry> journal) {

    public RunRecord {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        journal = journal == null ? Map.of() : Map.copyOf(journal);
    }

    /** The journal as a journal, in memory: reading it never touches the run. */
    public RunJournal asJournal() {
        RunJournal j = RunJournal.inMemory();
        journal.forEach(j::put);
        return j;
    }
}
