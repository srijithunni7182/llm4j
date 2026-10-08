package io.github.llm4j.loom.autonomy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Levels in memory: tests and one-shot runs. */
public class MemoryLevelStore implements LevelStore {

    private final Map<String, Map<String, LevelState>> levels = new LinkedHashMap<>();
    private final Map<String, Freeze> freezes = new LinkedHashMap<>();

    @Override
    public synchronized Optional<LevelState> get(String decision, String scope) {
        return Optional.ofNullable(levels.getOrDefault(decision, Map.of()).get(scope));
    }

    @Override
    public synchronized boolean compareAndSet(String decision, String scope, LevelState expected, LevelState next) {
        Map<String, LevelState> d = levels.computeIfAbsent(decision, k -> new LinkedHashMap<>());
        LevelState current = d.get(scope);
        if (current == null ? expected != null : (expected == null || current.version() != expected.version())) return false;
        d.put(scope, next);
        return true;
    }

    @Override
    public synchronized Map<String, LevelState> scopes(String decision) {
        return new LinkedHashMap<>(levels.getOrDefault(decision, Map.of()));
    }

    @Override
    public synchronized Optional<Freeze> freeze(String decision) {
        return Optional.ofNullable(freezes.get(decision));
    }

    @Override
    public synchronized void setFreeze(String decision, Freeze freeze) {
        freezes.put(decision, freeze);
    }

    @Override
    public synchronized void clearFreeze(String decision) {
        freezes.remove(decision);
    }
}
