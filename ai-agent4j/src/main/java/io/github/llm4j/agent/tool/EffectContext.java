package io.github.llm4j.agent.tool;


import io.github.llm4j.ratelimit.Sleeper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

/** What generic tools need from the run they're in. The executor supplies it; tests use {@link #noop()}. */
public interface EffectContext {

    void audit(String event, Map<String, Object> data);

    void trace(String text, Map<String, Object> data);

    EffectJournal journal();

    /** The step running on this thread, or "" outside one. */
    String currentStep();

    /** Changes each time a delegate starts on this thread, so a retried delegate numbers its calls afresh. */
    long attempt();

    /** Paths a {@code file} tool must never touch: the run journal and the trigger store. */
    Set<Path> reservedPaths();

    Sleeper sleeper();

    Clock clock();

    /** A context that records nothing: for tools used outside an executor. */
    static EffectContext noop() {
        return new EffectContext() {
            private final EffectJournal journal = EffectJournal.inMemory();

            @Override public void audit(String event, Map<String, Object> data) { }
            @Override public void trace(String text, Map<String, Object> data) { }
            @Override public EffectJournal journal() { return journal; }
            @Override public String currentStep() { return ""; }
            @Override public long attempt() { return 0; }
            @Override public Set<Path> reservedPaths() { return Set.of(); }
            @Override public Sleeper sleeper() { return Sleeper.SYSTEM; }
            @Override public Clock clock() { return Clock.systemUTC(); }
        };
    }
}
