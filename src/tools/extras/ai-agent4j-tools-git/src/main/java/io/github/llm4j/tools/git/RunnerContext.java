package io.github.llm4j.tools.git;

import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.EffectJournal;
import io.github.llm4j.ratelimit.Sleeper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

/**
 * The run's context for the sandbox under a git tool, with a journal of its own that lives as long
 * as the tool. The git tool journals its own effects (a commit, a push) in the run's journal, so a
 * resumed run does not repeat them; the git commands underneath must not be journaled again, or a
 * resumed run would get "already done" where it asked for a status.
 */
final class RunnerContext implements EffectContext {

    private final EffectContext run;
    private final EffectJournal journal = EffectJournal.inMemory();

    RunnerContext(EffectContext run) {
        this.run = run;
    }

    @Override
    public void audit(String event, Map<String, Object> data) {
        run.audit(event, data);
    }

    @Override
    public void trace(String text, Map<String, Object> data) {
        run.trace(text, data);
    }

    @Override
    public EffectJournal journal() {
        return journal;
    }

    @Override
    public String currentStep() {
        return run.currentStep();
    }

    @Override
    public String identityStep() {
        return run.identityStep();
    }

    @Override
    public boolean simulate() {
        return run.simulate();
    }

    @Override
    public long attempt() {
        return run.attempt();
    }

    @Override
    public Set<Path> reservedPaths() {
        return run.reservedPaths();
    }

    @Override
    public Sleeper sleeper() {
        return run.sleeper();
    }

    @Override
    public Clock clock() {
        return run.clock();
    }

    @Override
    public String lookup(String reference) {
        return run.lookup(reference);
    }
}
