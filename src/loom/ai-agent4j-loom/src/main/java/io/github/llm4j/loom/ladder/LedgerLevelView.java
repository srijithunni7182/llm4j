package io.github.llm4j.loom.ladder;

import io.github.llm4j.loom.autonomy.Level;
import io.github.llm4j.loom.autonomy.LevelState;
import io.github.llm4j.loom.autonomy.LevelStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where each scope stands, read from the ladder engine's record in the ledger and shown the way the
 * original level store shows it, so the code that prints a trust decision's status prints it from
 * the one record both engines have written. Read only: the ledger changes through the engine.
 */
public final class LedgerLevelView implements LevelStore {

    private final LevelLog log;

    public LedgerLevelView(LevelLog log) {
        this.log = log;
    }

    private static LevelState state(LevelLog.Standing s) {
        return new LevelState(
                Level.of(s.rungName()),
                s.epoch(),
                s.identity(),
                s.forced(),
                s.at(),
                s.reason(),
                s.version());
    }

    @Override
    public Optional<LevelState> get(String decision, String scope) {
        return log.current(scope).map(LedgerLevelView::state);
    }

    @Override
    public boolean compareAndSet(
            String decision, String scope, LevelState expected, LevelState next) {
        throw new UnsupportedOperationException(
                "the level of a decision on a ladder changes through its engine");
    }

    @Override
    public Map<String, LevelState> scopes(String decision) {
        Map<String, LevelState> out = new LinkedHashMap<>();
        log.all().forEach((scope, s) -> out.put(scope, state(s)));
        return out;
    }

    @Override
    public Optional<Freeze> freeze(String decision) {
        return log.frozen()
                ? Optional.of(
                        new Freeze(
                                log.freezeReason() == null ? "" : log.freezeReason(),
                                Instant.EPOCH))
                : Optional.empty();
    }

    @Override
    public void setFreeze(String decision, Freeze freeze) {
        throw new UnsupportedOperationException("a freeze is recorded through the engine");
    }

    @Override
    public void clearFreeze(String decision) {
        throw new UnsupportedOperationException("a freeze is recorded through the engine");
    }
}
