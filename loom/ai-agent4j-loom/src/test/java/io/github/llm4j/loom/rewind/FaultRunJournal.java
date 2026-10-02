package io.github.llm4j.loom.rewind;

import io.github.llm4j.loom.runtime.RunJournal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** A journal that "crashes" at the Nth write, either instead of it or just after it, so a test can stop a run between two entries. */
final class FaultRunJournal implements RunJournal {

    /** An Error, so nothing in a run can swallow it. */
    static final class Crash extends Error {
        Crash() {
            super("simulated crash", null, false, false);
        }
    }

    private final RunJournal delegate;
    private final AtomicInteger puts = new AtomicInteger();
    private final int crashAt;
    private final boolean afterWrite;

    /** @param crashAt the write to crash at (1 is the first), or 0 to never crash */
    FaultRunJournal(RunJournal delegate, int crashAt, boolean afterWrite) {
        this.delegate = delegate;
        this.crashAt = crashAt;
        this.afterWrite = afterWrite;
    }

    int puts() {
        return puts.get();
    }

    @Override
    public Optional<Entry> get(String stepId) {
        return delegate.get(stepId);
    }

    @Override
    public void put(String stepId, Entry entry) {
        int n = puts.incrementAndGet();
        if (n == crashAt && !afterWrite) throw new Crash();
        delegate.put(stepId, entry);
        if (n == crashAt) throw new Crash();
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
