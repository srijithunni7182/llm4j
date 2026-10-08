package io.github.llm4j.getviral.studio;

import io.github.llm4j.loom.runtime.RunSuspended;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One GetViral run: its event log (replayable, so a browser that connects late sees everything),
 * and the places a human steps in — picking the hook, the publish step and approving the publish.
 * A question never blocks a thread: it is announced, and the run suspends until the answer is
 * recorded in the run's journal and the run is resumed (see {@code GetViralEngine}).
 */
public class StudioRun implements StudioEvents {

    public enum Status { RUNNING, WAITING_FOR_HUMAN, DONE, BLOCKED, FAILED }

    /** Answers human questions on the spot when someone is at hand (terminal, tests). */
    public interface Autopilot {
        String answer(String kind, String message, List<String> options);

        boolean approve(String tool, Map<String, Object> args);
    }

    private final String id;
    private final Map<String, Object> brief;
    private final Instant startedAt = Instant.now();
    private final List<Map<String, Object>> log = new CopyOnWriteArrayList<>();
    private final List<Consumer<Map<String, Object>>> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();
    private volatile Autopilot autopilot;
    private volatile Status status = Status.RUNNING;

    public StudioRun(Map<String, Object> brief) {
        this(UUID.randomUUID().toString().substring(0, 8), brief);
    }

    /** A run with a caller-assigned id (the hosted app uses its database id). */
    public StudioRun(String id, Map<String, Object> brief) {
        this.id = id;
        this.brief = Map.copyOf(brief);
    }

    public String id() {
        return id;
    }

    public Map<String, Object> brief() {
        return brief;
    }

    public Status status() {
        return status;
    }

    public void status(Status status) {
        this.status = status;
        emit("status", Map.of("status", status.name()));
    }

    /** When set, human questions are answered on the spot instead of suspending the run. */
    public void autopilot(Autopilot autopilot) {
        this.autopilot = autopilot;
    }

    @Override
    public void emit(String type, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", seq.incrementAndGet());
        event.put("type", type);
        event.put("t", Duration.between(startedAt, Instant.now()).toMillis());
        event.put("data", data);
        log.add(event);
        for (Consumer<Map<String, Object>> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException e) {
                subscribers.remove(subscriber);
            }
        }
    }

    /** Replays the log so far, then streams new events. Returns an unsubscribe handle. */
    public synchronized Runnable subscribe(Consumer<Map<String, Object>> subscriber) {
        for (Map<String, Object> event : new ArrayList<>(log)) {
            subscriber.accept(event);
        }
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    /** Events emitted so far (a resumed hosted run includes the ones from before it was suspended). */
    public List<Map<String, Object>> events() {
        return List.copyOf(log);
    }

    /**
     * Asks the creator. With an autopilot the answer comes back now; otherwise the question is
     * announced under {@code questionId} and the run suspends ({@link RunSuspended}) until answered.
     */
    public String ask(String kind, String questionId, String message, List<String> options) {
        Autopilot pilot = autopilot;
        if (pilot != null) {
            String answer = pilot.answer(kind, message, options);
            emit("human_answer", Map.of("kind", kind, "answer", answer, "by", "autopilot"));
            return answer;
        }
        question(questionId, kind, message, options);
        status(Status.WAITING_FOR_HUMAN);
        throw new RunSuspended(questionId, message);
    }

    /** Announces an open question (the hosted app also stores it). */
    protected void question(String questionId, String kind, String message, List<String> options) {
        emit("human", Map.of("id", questionId, "kind", kind, "message", message, "options", options));
    }

    /** The Human-in-the-Loop gate for tools that declare requiresApproval(). */
    public boolean approve(String tool, Map<String, Object> args, String thought, String questionId) {
        emit("approval_request", Map.of("tool", tool, "args", args, "thought", thought == null ? "" : thought));
        Autopilot pilot = autopilot;
        if (pilot != null) {
            boolean ok = pilot.approve(tool, args);
            emit("approval_answer", Map.of("tool", tool, "approved", ok, "by", "autopilot"));
            return ok;
        }
        return "approve".equalsIgnoreCase(ask("approval", questionId, "Approve " + tool + "?", List.of("approve", "reject")));
    }
}
