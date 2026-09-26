package io.github.llm4j.getviral.app.runs;

import io.github.llm4j.getviral.studio.StudioRun;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link StudioRun} whose events and human questions live in the database instead of memory.
 * The engine is unchanged: it emits and asks exactly as it does locally.
 */
public class PersistentStudioRun extends StudioRun {

    private final RunStore store;
    private final RunRepository runs;
    private final Duration humanTimeout;
    private final long startedAt = System.currentTimeMillis();
    private final AtomicInteger seq = new AtomicInteger();

    public PersistentStudioRun(String id, Map<String, Object> brief, Duration humanTimeout, RunStore store,
                               RunRepository runs, int startSeq) {
        super(id, brief, humanTimeout);
        this.store = store;
        this.runs = runs;
        this.humanTimeout = humanTimeout;
        this.seq.set(startSeq);
    }

    @Override
    public void emit(String type, Map<String, Object> data) {
        store.append(id(), seq.incrementAndGet(), type, data, System.currentTimeMillis() - startedAt);
        if ("status".equals(type)) {
            runs.updateStatus(id(), RunStatus.valueOf(String.valueOf(data.get("status"))), Instant.now());
        } else {
            runs.touch(id(), Instant.now());
        }
    }

    @Override
    public String ask(String kind, String message, List<String> options, String timeoutDefault) {
        String questionId = kind + "-" + id().substring(0, 8) + "-" + (seq.get() + 1);
        store.createQuestion(questionId, id(), kind, message, options);
        status(Status.WAITING_FOR_HUMAN);
        emit("human", Map.of("id", questionId, "kind", kind, "message", message, "options", options));
        long deadline = System.currentTimeMillis() + humanTimeout.toMillis();
        try {
            while (System.currentTimeMillis() < deadline) {
                Optional<String> answer = store.answer(questionId);
                if (answer.isPresent()) {
                    emit("human_answer", Map.of("kind", kind, "answer", answer.get(), "by", "creator"));
                    return answer.get();
                }
                Thread.sleep(400);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            status(Status.RUNNING);
        }
        store.submitAnswer(id(), questionId, timeoutDefault);
        emit("human_answer", Map.of("kind", kind, "answer", timeoutDefault, "by", "timeout"));
        return timeoutDefault;
    }
}
