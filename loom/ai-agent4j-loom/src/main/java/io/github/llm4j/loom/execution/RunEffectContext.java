package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.tools.generic.EffectContext;
import io.github.llm4j.ratelimit.Sleeper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

/** The executor's view for generic tools: the run's audit log, trace, journal, clock and current step. */
final class RunEffectContext implements EffectContext {

    private final HarnessExecutor executor;

    RunEffectContext(HarnessExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void audit(String event, Map<String, Object> data) {
        executor.audit(event, data);
    }

    @Override
    public void trace(String text, Map<String, Object> data) {
        executor.trace(TraceEvent.TOOL, null, text, data);
    }

    @Override
    public RunJournal journal() {
        return executor.getJournal();
    }

    @Override
    public String currentStep() {
        return executor.currentStep();
    }

    @Override
    public long attempt() {
        return executor.currentAttempt();
    }

    @Override
    public Set<Path> reservedPaths() {
        return executor.reservedPaths();
    }

    @Override
    public Sleeper sleeper() {
        return executor.sleeper();
    }

    @Override
    public Clock clock() {
        return executor.clock();
    }
}
