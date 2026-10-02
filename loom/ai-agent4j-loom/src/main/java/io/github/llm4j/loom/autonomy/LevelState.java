package io.github.llm4j.loom.autonomy;

import java.time.Instant;

/**
 * Where a ladder stands: the level, the evidence epoch it belongs to, the identity of the agent that earned it, and how it got there.
 *
 * @param version incremented on every change, so a compare-and-set can tell it has been beaten to it
 * @param forced  set by hand beyond what the evidence supports; shown as forced until the evidence catches up
 */
public record LevelState(Level level, int epoch, String identity, boolean forced, Instant since, String reason, int version) {

    public LevelState withLevel(Level next, boolean forced, Instant at, String reason) {
        return new LevelState(next, epoch, identity, forced, at, reason, version + 1);
    }

    public LevelState withEpoch(Level next, int newEpoch, String newIdentity, Instant at, String reason) {
        return new LevelState(next, newEpoch, newIdentity, false, at, reason, version + 1);
    }
}
