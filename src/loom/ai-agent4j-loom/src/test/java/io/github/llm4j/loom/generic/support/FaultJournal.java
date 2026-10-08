package io.github.llm4j.loom.generic.support;

import io.github.llm4j.loom.runtime.RunJournal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps a journal and "crashes" after a number of writes, so a test can stop a run exactly between two
 * journal entries and then run again on the same underlying journal.
 */
public final class FaultJournal implements RunJournal {

    /** An {@link Error}, so an agent's own exception handling can't swallow the crash. */
    public static final class SimulatedCrash extends Error {
        public SimulatedCrash() {
            super("simulated crash", null, false, false);
        }
    }

    private final RunJournal delegate;
    private final AtomicInteger putsLeft;
    private final boolean crashAfterWrite;

    /**
     * @param putsAllowed writes that succeed before the crash
     * @param crashAfterWrite true: the write that hits the limit is stored and then the crash happens (the
     *                        process died just after it); false: the crash happens instead of the write
     */
    public FaultJournal(RunJournal delegate, int putsAllowed, boolean crashAfterWrite) {
        this.delegate = delegate;
        this.putsLeft = new AtomicInteger(putsAllowed);
        this.crashAfterWrite = crashAfterWrite;
    }

    @Override
    public Optional<Entry> get(String stepId) {
        return delegate.get(stepId);
    }

    @Override
    public void put(String stepId, Entry entry) {
        if (putsLeft.getAndDecrement() <= 0) throw new SimulatedCrash();
        delegate.put(stepId, entry);
        if (crashAfterWrite && putsLeft.get() <= 0) throw new SimulatedCrash();
    }

    @Override
    public Map<String, Entry> all() {
        return delegate.all();
    }

    @Override
    public boolean isDurable() {
        return delegate.isDurable();
    }
}
