package io.github.llm4j.loom.host;

import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.time.Clock;
import java.util.List;

/**
 * A person who answers later. The first time a step asks, the question is recorded and the listeners told, and the run suspends, holding no thread. When
 * the run is resumed it asks again and either gets the answer given meanwhile or suspends again.
 */
final class HostHumanInterface implements HumanInterface {

    private final String runId;
    private final AnswerBook book;
    private final List<QuestionListener> listeners;
    private final Clock clock;

    HostHumanInterface(String runId, AnswerBook book, List<QuestionListener> listeners, Clock clock) {
        this.runId = runId;
        this.book = book;
        this.listeners = listeners;
        this.clock = clock;
    }

    @Override
    public String promptHuman(String message) {
        throw new IllegalStateException("a question that is answered later needs the step it belongs to");
    }

    @Override
    public String promptHuman(String stepId, String message) {
        return promptHuman(stepId, message, Hints.none());
    }

    @Override
    public String promptHuman(String stepId, String message, Hints hints) {
        AnswerBook.Question existing = book.find(runId, stepId).orElse(null);
        if (existing != null && existing.answered()) return existing.answer();
        if (existing == null) {
            book.ask(runId, stepId, message, hints.choices(), clock.instant());
            for (QuestionListener l : listeners) l.asked(runId, stepId, message, hints.choices());
        }
        throw new RunSuspended(stepId, message);
    }
}
