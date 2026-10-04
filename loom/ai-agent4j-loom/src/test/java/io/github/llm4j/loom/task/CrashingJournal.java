package io.github.llm4j.loom.task;

import io.github.llm4j.loom.runtime.RunJournal;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

/** A journal that "dies" (throws an Error nothing can swallow) just before a chosen write, so a test can stop a run between two entries. */
final class CrashingJournal implements RunJournal {

    static final class Crash extends Error {
        Crash(String where) {
            super("simulated crash before writing " + where, null, false, false);
        }
    }

    private final RunJournal delegate;
    private final BiPredicate<String, Entry> crashBefore;
    private boolean crashed;

    CrashingJournal(RunJournal delegate, BiPredicate<String, Entry> crashBefore) {
        this.delegate = delegate;
        this.crashBefore = crashBefore;
    }

    /** The journal underneath: what a restarted process finds. */
    RunJournal delegateForTest() {
        return delegate;
    }

    boolean crashed() {
        return crashed;
    }

    @Override
    public Optional<Entry> get(String stepId) {
        return delegate.get(stepId);
    }

    @Override
    public void put(String stepId, Entry entry) {
        if (!crashed && crashBefore.test(stepId, entry)) {
            crashed = true;
            throw new Crash(stepId + " (" + entry.kind() + ")");
        }
        delegate.put(stepId, entry);
    }

    @Override
    public Map<String, Entry> all() {
        return delegate.all();
    }
}
