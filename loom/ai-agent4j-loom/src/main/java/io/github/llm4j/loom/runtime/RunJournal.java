package io.github.llm4j.loom.runtime;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The durable record of a workflow run. Every step with side effects (a delegate, a human answer, a
 * broadcast) writes its result here under a stable step id — its position in the script, including
 * loop round and branch. Re-running the workflow with the same journal replays those results instead
 * of calling models again, so a run can stop (a restart, a human who answers tomorrow) and carry on
 * exactly where it left off, on any machine.
 */
public interface RunJournal {

    /** A recorded step result. {@code kind} is "delegate", "failed", "human", "broadcast" or "handoff". */
    record Entry(String kind, Object value) { }

    Optional<Entry> get(String stepId);

    void put(String stepId, Entry entry);

    /** Records a human's answer for a suspended step; run the workflow again to continue. */
    default void answer(String stepId, Object value) {
        put(stepId, new Entry("human", value));
    }

    /** Every recorded step, for inspection. */
    Map<String, Entry> all();

    /** A journal that lives as long as the executor: runs are replayable within one process. */
    static RunJournal inMemory() {
        return new RunJournal() {
            private final Map<String, Entry> entries = new ConcurrentHashMap<>();

            @Override
            public Optional<Entry> get(String stepId) {
                return Optional.ofNullable(entries.get(stepId));
            }

            @Override
            public void put(String stepId, Entry entry) {
                entries.put(stepId, entry);
            }

            @Override
            public Map<String, Entry> all() {
                return Map.copyOf(entries);
            }
        };
    }
}
