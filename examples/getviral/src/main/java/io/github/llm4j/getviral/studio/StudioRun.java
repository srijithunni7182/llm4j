package io.github.llm4j.getviral.studio;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One GetViral run: its event log (replayable, so a browser that connects late sees everything),
 * and the two places a human steps in — picking the hook and approving the Instagram publish.
 */
public class StudioRun implements StudioEvents {

    public enum Status { RUNNING, WAITING_FOR_HUMAN, DONE, BLOCKED, FAILED }

    /** Decides human questions when nobody is watching (CLI piping, tests, timeouts). */
    public interface Autopilot {
        String answer(String kind, String message, List<String> options);

        boolean approve(String tool, Map<String, Object> args);
    }

    private final String id;
    private final Map<String, Object> brief;
    private final Instant startedAt = Instant.now();
    private final List<Map<String, Object>> log = new CopyOnWriteArrayList<>();
    private final List<Consumer<Map<String, Object>>> subscribers = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final Duration humanTimeout;
    private volatile Autopilot autopilot;
    private volatile Status status = Status.RUNNING;

    public StudioRun(Map<String, Object> brief, Duration humanTimeout) {
        this(UUID.randomUUID().toString().substring(0, 8), brief, humanTimeout);
    }

    /** A run with a caller-assigned id (the hosted app uses its database id). */
    public StudioRun(String id, Map<String, Object> brief, Duration humanTimeout) {
        this.id = id;
        this.brief = Map.copyOf(brief);
        this.humanTimeout = humanTimeout;
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

    /** When set, human questions are answered automatically instead of waiting for the UI. */
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

    public List<Map<String, Object>> events() {
        return List.copyOf(log);
    }

    /** Blocks the workflow until a human answers (or the autopilot / timeout default does). */
    public String ask(String kind, String message, List<String> options, String timeoutDefault) {
        Autopilot pilot = autopilot;
        if (pilot != null) {
            String answer = pilot.answer(kind, message, options);
            emit("human_answer", Map.of("kind", kind, "answer", answer, "by", "autopilot"));
            return answer;
        }
        String questionId = kind + "-" + seq.get();
        CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(questionId, future);
        Status previous = status;
        status(Status.WAITING_FOR_HUMAN);
        emit("human", Map.of("id", questionId, "kind", kind, "message", message, "options", options));
        try {
            String answer = future.get(humanTimeout.toMillis(), TimeUnit.MILLISECONDS);
            emit("human_answer", Map.of("kind", kind, "answer", answer, "by", "creator"));
            return answer;
        } catch (TimeoutException e) {
            emit("human_answer", Map.of("kind", kind, "answer", timeoutDefault, "by", "timeout"));
            return timeoutDefault;
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return timeoutDefault;
        } finally {
            pending.remove(questionId);
            status(previous == Status.WAITING_FOR_HUMAN ? Status.RUNNING : previous);
        }
    }

    /** The Human-in-the-Loop gate for tools that declare requiresApproval(). */
    public boolean approve(String tool, Map<String, Object> args, String thought) {
        emit("approval_request", Map.of("tool", tool, "args", args, "thought", thought == null ? "" : thought));
        Autopilot pilot = autopilot;
        if (pilot != null) {
            boolean ok = pilot.approve(tool, args);
            emit("approval_answer", Map.of("tool", tool, "approved", ok, "by", "autopilot"));
            return ok;
        }
        String answer = ask("approval", "Approve " + tool + "?", List.of("approve", "reject"), "reject");
        boolean ok = "approve".equalsIgnoreCase(answer);
        emit("approval_answer", Map.of("tool", tool, "approved", ok, "by", "creator"));
        return ok;
    }

    /** Called by the web layer with the creator's answer. */
    public boolean answer(String questionId, String answer) {
        CompletableFuture<String> future = pending.get(questionId);
        return future != null && future.complete(answer);
    }

    public boolean hasPendingQuestion() {
        return !pending.isEmpty();
    }
}
