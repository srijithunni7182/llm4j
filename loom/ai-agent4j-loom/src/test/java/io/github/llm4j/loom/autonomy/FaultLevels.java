package io.github.llm4j.loom.autonomy;

import java.util.Map;
import java.util.Optional;

/** A level store that can be made to fail on read or on write. */
final class FaultLevels implements LevelStore {

    private final LevelStore delegate;
    boolean failReads;
    boolean failWrites;

    FaultLevels(LevelStore delegate) {
        this.delegate = delegate;
    }

    private void read() {
        if (failReads) throw new IllegalStateException("the level store is down (read)");
    }

    @Override
    public Optional<LevelState> get(String decision, String scope) {
        read();
        return delegate.get(decision, scope);
    }

    @Override
    public boolean compareAndSet(String decision, String scope, LevelState expected, LevelState next) {
        if (failWrites) throw new IllegalStateException("the level store is down (write)");
        return delegate.compareAndSet(decision, scope, expected, next);
    }

    @Override
    public Map<String, LevelState> scopes(String decision) {
        read();
        return delegate.scopes(decision);
    }

    @Override
    public Optional<Freeze> freeze(String decision) {
        read();
        return delegate.freeze(decision);
    }

    @Override
    public void setFreeze(String decision, Freeze freeze) {
        delegate.setFreeze(decision, freeze);
    }

    @Override
    public void clearFreeze(String decision) {
        delegate.clearFreeze(decision);
    }
}
