package io.github.llm4j.getviral.app.runs;

import io.github.llm4j.getviral.studio.StudioRun;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link StudioRun} whose events and open questions live in the database, so any instance can
 * stream it, answer it and resume it. The engine is unchanged: it emits and asks as it does locally.
 */
public class PersistentStudioRun extends StudioRun {

    private final RunStore store;
    private final RunRepository runs;
    private final long startedAt = System.currentTimeMillis();
    private final AtomicInteger seq;

    public PersistentStudioRun(String id, Map<String, Object> brief, RunStore store, RunRepository runs) {
        super(id, brief);
        this.store = store;
        this.runs = runs;
        this.seq = new AtomicInteger(store.lastSeq(id));
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

    /** Stores the question (once per step) so the creator can answer it from any instance. */
    @Override
    protected void question(String stepId, String kind, String message, List<String> options) {
        String questionId = store.openQuestionFor(id(), stepId)
                .orElseGet(() -> {
                    String qid = UUID.randomUUID().toString();
                    store.createQuestion(qid, id(), stepId, kind, message, options);
                    return qid;
                });
        super.question(questionId, kind, message, options);
    }

    /** The whole run's log, including events from before it was last suspended. */
    @Override
    public List<Map<String, Object>> events() {
        return store.eventsAfter(id(), 0, 100_000);
    }
}
