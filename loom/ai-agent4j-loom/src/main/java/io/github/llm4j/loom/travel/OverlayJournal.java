package io.github.llm4j.loom.travel;

import io.github.llm4j.loom.runtime.RunJournal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A journal laid over another: reads look in what this run wrote and then in the journal underneath, writes go only to this layer, and
 * the one underneath is never touched. It is how a run can be tried against a copy of the past without copying it, and without any way
 * to change it.
 */
public final class OverlayJournal implements RunJournal {

    private final RunJournal below;
    private final Map<String, Entry> layer = new ConcurrentHashMap<>();

    public OverlayJournal(RunJournal below) {
        this.below = below;
    }

    @Override
    public Optional<Entry> get(String stepId) {
        Entry own = layer.get(stepId);
        return own != null ? Optional.of(own) : below.get(stepId);
    }

    @Override
    public void put(String stepId, Entry entry) {
        layer.put(stepId, entry);
    }

    @Override
    public Map<String, Entry> all() {
        Map<String, Entry> merged = new LinkedHashMap<>(below.all());
        merged.putAll(layer);
        return merged;
    }

    /** What this run wrote itself. */
    public Map<String, Entry> written() {
        return Map.copyOf(layer);
    }
}
