package io.github.llm4j.spring;

import io.github.llm4j.loom.trigger.TriggerRunner;
import java.util.List;
import java.util.logging.Logger;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;

/**
 * Starts the trigger runner after the context is up (when {@code llm4j.loom.triggers.enabled}) and, on shutdown, stops it first, asks running workflows to
 * wrap up, waits {@code llm4j.loom.shutdown-grace}, then suspends what is left (a run with a store resumes later) or cancels it.
 */
final class Llm4jLoomLifecycle implements SmartLifecycle {

    private static final Logger log = Logger.getLogger(Llm4jLoomLifecycle.class.getName());

    private final LoomRuntime runtime;
    private final Llm4jProperties.Loom settings;
    private final ApplicationEventPublisher events;
    private volatile boolean running;

    Llm4jLoomLifecycle(LoomRuntime runtime, Llm4jProperties.Loom settings, ApplicationEventPublisher events) {
        this.runtime = runtime;
        this.settings = settings;
        this.events = events;
    }

    @Override
    public void start() {
        if (settings.triggers().enabled()) {
            runtime.host().startTriggers(settings.triggers().pollInterval(), settings.triggers().owner(), settings.triggers().staleAfter(), this::fired);
        }
        running = true;
    }

    private void fired(TriggerRunner.Fired f) {
        events.publishEvent(new TriggerFiredEvent(f.trigger().id(), f.runId(), String.valueOf(f.outcome()), f.lateMillis()));
    }

    @Override
    public void stop() {
        List<String> left = runtime.shutdown(settings.shutdownGrace());
        if (!left.isEmpty()) log.info("workflow runs still going when the application stopped: " + left);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stops early in the shutdown, before the things a run uses (a database, say) go away. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE / 2;
    }
}
