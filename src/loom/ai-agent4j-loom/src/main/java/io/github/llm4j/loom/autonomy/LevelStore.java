package io.github.llm4j.loom.autonomy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** The level of each (decision, scope) ladder, and the freeze flags. Only the runtime and the {@code weave autonomy} commands write it. */
public interface LevelStore {

    /** A freeze: {@code act} is stopped for a decision (or for all, named {@code *}). */
    record Freeze(String reason, Instant at) { }

    Optional<LevelState> get(String decision, String scope);

    /**
     * Replaces the state when it is still {@code expected} (null: when there is none), and says whether it did. Two runs, or a run and a
     * command, that both try to move a level cannot both succeed.
     */
    boolean compareAndSet(String decision, String scope, LevelState expected, LevelState next);

    /** Every scope of the decision and where it stands. */
    Map<String, LevelState> scopes(String decision);

    Optional<Freeze> freeze(String decision);

    void setFreeze(String decision, Freeze freeze);

    void clearFreeze(String decision);

    /** True when the decision, or everything, is frozen. */
    default boolean frozen(String decision) {
        return freeze(decision).isPresent() || freeze("*").isPresent();
    }
}
