package io.github.llm4j.tools;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a tool records that a side effect is about to happen, and that it happened, so a repeat of the same
 * call after a crash is recognised. A host adapts its own durable store to this (Loom's run journal does);
 * {@link #inMemory()} serves a process that doesn't need to survive a restart.
 */
public interface EffectJournal {

    /** A recorded step: {@code kind} says what it is, {@code value} is its text. */
    record Entry(String kind, Object value) { }

    Optional<Entry> get(String key);

    void put(String key, Entry entry);

    /** Every recorded entry, for counting calls. */
    Map<String, Entry> all();

    static EffectJournal inMemory() {
        return new EffectJournal() {
            private final Map<String, Entry> entries = new ConcurrentHashMap<>();

            @Override public Optional<Entry> get(String key) { return Optional.ofNullable(entries.get(key)); }
            @Override public void put(String key, Entry entry) { entries.put(key, entry); }
            @Override public Map<String, Entry> all() { return Map.copyOf(entries); }
        };
    }
}
