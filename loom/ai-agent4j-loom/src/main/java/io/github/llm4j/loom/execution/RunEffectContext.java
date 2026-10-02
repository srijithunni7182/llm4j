package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.EffectJournal;
import io.github.llm4j.ratelimit.Sleeper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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

    /** One object for the whole run, so the tools' lock on it is the same lock every time. */
    private final EffectJournal journal = new EffectJournal() {
        @Override
        public Optional<Entry> get(String key) {
            return executor.getJournal().get(key).map(e -> new Entry(e.kind(), e.value()));
        }

        @Override
        public void put(String key, Entry entry) {
            executor.getJournal().put(key, new RunJournal.Entry(entry.kind(), entry.value()));
        }

        @Override
        public Map<String, Entry> all() {
            Map<String, Entry> out = new LinkedHashMap<>();
            executor.getJournal().all().forEach((k, e) -> out.put(k, new Entry(e.kind(), e.value())));
            return out;
        }
    };

    @Override
    public EffectJournal journal() {
        return journal;
    }

    @Override
    public String currentStep() {
        return executor.currentStep();
    }

    @Override
    public String identityStep() {
        return executor.identityStep();
    }

    @Override
    public boolean simulate() {
        return executor.simulating();
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
