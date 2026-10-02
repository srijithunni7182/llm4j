package io.github.llm4j.loom.autonomy;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.execution.HarnessExecutor;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * {@code when the agent changes: test it on past cases}: before the first case of a new epoch, the most recent window of the old epoch's blind
 * cases is replayed under the new agent, and the new agent inherits the highest level whose rule that replay satisfies, at most the level the old
 * agent had. With too few replayable cases it starts at the declared start level. It is the replay engine, used on the old epoch's cases.
 */
public final class EpochReplay implements HarnessExecutor.Inheritance {

    private static final Logger log = Logger.getLogger(EpochReplay.class.getName());

    private final ReplayEngine engine;
    private final Path script;

    /** @param script the script file the new agent is defined in */
    public EpochReplay(ReplayEngine engine, Path script) {
        this.engine = engine;
        this.script = script;
    }

    @Override
    public HarnessExecutor.Inherited inherited(DecisionDef def, String scope, LevelState old, String identity) {
        try {
            ReplayReport report = engine.run(def.getName(), new ReplayOptions(script, scope, null, def.getWindow(), 0, 1, false, false, false, 0, null, null), old.epoch());
            DecisionDef.UpRule first = def.getUpRules().get(Level.SUGGEST);
            int needed = first == null ? 1 : first.cases();
            if (report.replayed < needed) {
                return new HarnessExecutor.Inherited(def.getStartAt(), "replay " + report.id + " had too few replayable cases (" + report.replayed + " of " + report.selected
                        + ", the first rule needs " + needed + "), so it starts at " + def.getStartAt().word());
            }
            Level earned = Level.of(report.levelEarned);
            Level level = Level.min(earned == null ? def.getStartAt() : earned, old.level());
            return new HarnessExecutor.Inherited(level, "replay " + report.id + " over " + report.replayed + " past cases: " + report.levelEarnedWhy);
        } catch (Exception e) {
            log.warning("Decision " + def.getName() + ": the replay of past cases failed (" + e.getMessage() + "); the new agent starts over");
            return new HarnessExecutor.Inherited(def.getStartAt(), "the replay of past cases failed (" + e.getMessage() + "), so it starts at " + def.getStartAt().word());
        }
    }
}
