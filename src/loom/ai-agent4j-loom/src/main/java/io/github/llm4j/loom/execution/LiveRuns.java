package io.github.llm4j.loom.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The runs going on in this process right now, by label, so that whatever listens in the same
 * process (the console, a chat channel, an embedding application's own endpoint) can reach their
 * agents. A run that is suspended or finished is not here: there is nothing left to steer, and a
 * stop or answer for it travels through the journal instead.
 */
public final class LiveRuns {

    private static final LiveRuns SHARED = new LiveRuns();

    /** One live run: how a person names it, and the executor that holds its agents. */
    public record Entry(String label, HarnessExecutor executor) {}

    private final List<Entry> runs = new CopyOnWriteArrayList<>();

    /** The registry of this process. */
    public static LiveRuns shared() {
        return SHARED;
    }

    /** Adds a run; the returned token removes it (use it in a {@code finally}). */
    public AutoCloseable register(String label, HarnessExecutor executor) {
        Entry entry = new Entry(label, executor);
        runs.add(entry);
        return () -> runs.remove(entry);
    }

    public List<Entry> all() {
        return new ArrayList<>(runs);
    }

    public boolean isEmpty() {
        return runs.isEmpty();
    }
}
