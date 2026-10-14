package io.github.llm4j.telemetry;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Lets the layers under a model call say what they did, without any method signature changing.
 *
 * <p>A <b>thread-local stack of recorders</b>. The caller that wants the figures (the agent, or your own code) calls
 * {@link #begin()} before the model call and {@link #end()} after it, in a {@code try/finally}. While a recorder is in
 * force, the HTTP layer reports each request it sends ({@link #attempt()}) and each sleep between requests
 * ({@link #waited(Duration)}), and the routing client reports each client it gave up on ({@link #failover(String)}).
 * With no {@code begin()} in force every one of these does nothing, so a library user who never asks pays nothing.
 *
 * <p>The stack makes nesting correct: a tool that itself runs an agent on the same thread gets its own recorder and
 * cannot disturb the outer one. Work done on <b>other threads</b> is not attributed (the figures for it are absent,
 * not wrong).
 *
 * <pre>{@code
 * CallTrace.begin();
 * try {
 *     client.chat(request);
 * } finally {
 *     CallTrace.Totals t = CallTrace.end();
 *     System.out.println(t.attempts() + " requests, waited " + t.waitedMillis() + " ms, failed over from " + t.failovers());
 * }
 * }</pre>
 */
public final class CallTrace {

    private CallTrace() {}

    /**
     * What happened under one traced call.
     *
     * @param attempts requests sent; at least 1 (a call that sent nothing through the HTTP layer counts as one)
     * @param waitedMillis time spent sleeping between attempts
     * @param failovers the clients that failed before one answered, in order
     */
    public record Totals(int attempts, long waitedMillis, List<String> failovers) {
        /** What an untraced call looks like: one attempt, no waiting, no fail-over. */
        public static final Totals NOTHING = new Totals(1, 0, List.of());

        public Totals {
            if (attempts < 1) throw new IllegalArgumentException("attempts must be at least 1, got " + attempts);
            if (waitedMillis < 0) throw new IllegalArgumentException("waitedMillis must not be negative, got " + waitedMillis);
            failovers = failovers == null ? List.of() : List.copyOf(failovers);
        }
    }

    private static final class Recorder {
        int attempts;
        long waited;
        final List<String> failovers = new ArrayList<>();
    }

    private static final ThreadLocal<Deque<Recorder>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    /** Starts a recorder for the calls this thread makes until the matching {@link #end()}. */
    public static void begin() {
        STACK.get().push(new Recorder());
    }

    /**
     * Stops the innermost recorder and returns what it saw. Safe to call with nothing in force (returns
     * {@link Totals#NOTHING}), so it can sit in a {@code finally} block.
     */
    public static Totals end() {
        Deque<Recorder> stack = STACK.get();
        Recorder r = stack.poll();
        if (stack.isEmpty()) STACK.remove();
        if (r == null) return Totals.NOTHING;
        return new Totals(Math.max(1, r.attempts), r.waited, r.failovers);
    }

    /** True while a recorder is in force on this thread. */
    public static boolean active() {
        Deque<Recorder> stack = STACK.get();
        boolean on = !stack.isEmpty();
        if (!on) STACK.remove();
        return on;
    }

    /** Called by the HTTP layer for each request it sends. */
    public static void attempt() {
        Recorder r = current();
        if (r != null) r.attempts++;
    }

    /** Called by the HTTP layer for each sleep between attempts (a back-off or a rate-limit wait). */
    public static void waited(Duration d) {
        if (d == null || d.isNegative()) return;
        Recorder r = current();
        if (r != null) r.waited += d.toMillis();
    }

    /** Called by the routing client when it moves on from a client that failed. */
    public static void failover(String clientName) {
        if (clientName == null) return;
        Recorder r = current();
        if (r != null) r.failovers.add(clientName);
    }

    private static Recorder current() {
        Deque<Recorder> stack = STACK.get();
        if (stack.isEmpty()) {
            STACK.remove();
            return null;
        }
        return stack.peek();
    }
}
