package io.github.llm4j.loom.channel;

import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.time.Clock;
import java.util.logging.Logger;

/**
 * A person who is not at the console. The first time a step asks, the question is recorded and sent, and the run is suspended; asking again
 * (the run was resumed) sends nothing and either returns the answer that arrived or suspends again.
 */
public final class ChannelHumanInterface implements HumanInterface {

    private static final Logger log = Logger.getLogger(ChannelHumanInterface.class.getName());

    private final PendingStore store;
    private final Dispatch dispatch;
    private final String run;
    private final String runId;
    private final Clock clock;

    /**
     * @param run   the run directory
     * @param runId how resume triggers name the run
     */
    public ChannelHumanInterface(PendingStore store, Dispatch dispatch, String run, String runId, Clock clock) {
        this.store = store;
        this.dispatch = dispatch;
        this.run = run;
        this.runId = runId;
        this.clock = clock;
    }

    @Override
    public String promptHuman(String message) {
        throw new IllegalStateException("a question sent through a channel needs the step it belongs to");
    }

    @Override
    public String promptHuman(String stepId, String message) {
        return promptHuman(stepId, message, Hints.none());
    }

    @Override
    public String promptHuman(String stepId, String message, Hints hints) {
        Pending p = store.locked(() -> {
            Pending found = store.find(run, stepId).orElse(null);
            if (found != null) return found;
            Pending fresh = new Pending(store.freshCode(), run, runId, stepId, Text.safe(message, true), hints.choices(),
                    hints.kind() == Hints.Kind.APPROVAL ? "approval" : hints.kind() == Hints.Kind.DECIDE ? "decide" : "prompt", hints.to(), clock.instant());
            store.put(fresh);
            return fresh;
        });
        switch (p.state()) {
            case ANSWERED:
                return p.answer().text();
            case EXPIRED:
                return "";
            default:
                break;
        }
        if (p.sent().isEmpty()) {
            store.locked(() -> {
                Pending fresh = store.get(p.code()).orElse(p);
                if (fresh.open() && fresh.sent().isEmpty() && dispatch.send(fresh)) store.put(fresh);
                return null;
            });
        }
        log.info("Waiting for an answer to " + p.code() + " (run " + run + ")");
        throw new RunSuspended(stepId, message);
    }
}
