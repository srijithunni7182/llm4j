package io.github.llm4j.loom.channel;

import io.github.llm4j.loom.runtime.HumanInterface;
import java.nio.file.Path;
import java.time.Clock;

/** The console, for a store that has questions: an answer given here closes the question that was sent to a channel for the same step. */
public final class ClosingHumanInterface implements HumanInterface {

    private final HumanInterface inner;
    private final PendingStore pending;
    private final Answers answers;
    private final String run;

    public ClosingHumanInterface(HumanInterface inner, Path store, String run, Clock clock) {
        this.inner = inner;
        this.pending = new PendingStore(store);
        this.answers = Channels.answersFor(store, clock);
        this.run = run;
    }

    @Override
    public String promptHuman(String message) {
        return inner.promptHuman(message);
    }

    @Override
    public String promptHuman(String stepId, String message) {
        return close(stepId, inner.promptHuman(stepId, message));
    }

    @Override
    public String promptHuman(String stepId, String message, Hints hints) {
        return close(stepId, inner.promptHuman(stepId, message, hints));
    }

    private String close(String stepId, String answer) {
        pending.find(run, stepId).filter(Pending::open).ifPresent(p -> answers.record(p.code(), answer, "console", false));
        return answer;
    }
}
