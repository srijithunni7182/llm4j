package io.github.llm4j.loom.autonomy;

import io.github.llm4j.loom.runtime.RunJournal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** A journal that "crashes" at the Nth write, instead of it or just after it. */
final class FaultJournal implements RunJournal {

    static final class Crash extends Error {
        Crash() {
            super("simulated crash", null, false, false);
        }
    }

    private final RunJournal delegate;
    private final AtomicInteger puts = new AtomicInteger();
    private final int crashAt;
    private final boolean afterWrite;

    FaultJournal(RunJournal delegate, int crashAt, boolean afterWrite) {
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
