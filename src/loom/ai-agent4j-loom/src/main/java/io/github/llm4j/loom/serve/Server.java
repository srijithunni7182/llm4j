package io.github.llm4j.loom.serve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * {@code weave serve}: JSON-RPC 2.0 over a pair of streams with {@code Content-Length} framing. It
 * answers requests, keeps no state a client has to know about beyond the folders it opened, and
 * does not stop for a bad message: a malformed frame, a handler that throws, a request that takes
 * too long each become an error reply and the next request is served. It ends at a {@code
 * loom/shutdown} or when the input closes.
 *
 * <p>What a method does is the {@link Engine}'s business; this class owns the protocol: the
 * handshake, ids, cancellation, time limits, and the workspace boundary.
 */
public final class Server {

    /** The protocol version this server speaks. */
    public static final int PROTOCOL = 1;

    /** What a request needs from the outside: the methods, and the folders they may read. */
    public interface Engine {

        /** One method: its params, the boundary, and the answer (an object). */
        @FunctionalInterface
        interface Method {
            JsonNode call(Request request) throws Exception;
        }

        Map<String, Method> methods();

        /** The version string reported by {@code initialize}. */
        String version();
    }

    /** What a method is handed. */
    public record Request(
            String method, JsonNode params, Workspace workspace, Cancellation cancellation) {

        /** The path in {@code params.file}, inside the workspace. */
        public Path file() throws RpcError {
            return workspace.resolve(params.path("file").asText(null));
        }

        /**
         * The unsaved text the client sent: {@code params.text} for {@code params.file}, and {@code
         * params.buffers} (file to text) for the other files it has open and changed, so an import
         * that is being edited is read as the editor has it.
         */
        public Map<Path, String> texts() throws RpcError {
            Map<Path, String> out = new java.util.LinkedHashMap<>();
            JsonNode buffers = params.get("buffers");
            if (buffers != null && buffers.isObject()) {
                var fields = buffers.fields();
                while (fields.hasNext()) {
                    var f = fields.next();
                    out.put(workspace.resolve(f.getKey()), f.getValue().asText());
                }
            }
            if (params.hasNonNull("text")) out.put(file(), params.get("text").asText());
            return out;
        }
    }

    /** Whether the client has withdrawn the request. */
    public interface Cancellation {
        boolean cancelled();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Requests that may run at once; more wait in the queue. */
    public static final int WORKERS = 4;

    /** Requests that may wait; past this the answer is "busy" at once, never a stall. */
    public static final int QUEUE = 64;

    /**
     * Workers that were given up on (timed out or replaced) and have not finished; past this the
     * server exits to be restarted.
     */
    public static final int MAX_ABANDONED = 4;

    private final Engine engine;
    private final long timeoutMillis;
    private final java.util.concurrent.ThreadPoolExecutor pool =
            new java.util.concurrent.ThreadPoolExecutor(
                    WORKERS,
                    WORKERS,
                    30,
                    TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(QUEUE),
                    r -> {
                        Thread t = new Thread(r, "weave-serve-worker");
                        t.setDaemon(true);
                        return t;
                    });
    private final java.util.concurrent.ScheduledExecutorService clock =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "weave-serve-timer");
                        t.setDaemon(true);
                        return t;
                    });

    /**
     * Replies wait here for the writer, so a worker never blocks on a client that has stopped
     * reading.
     */
    private final java.util.concurrent.BlockingQueue<String> outbox =
            new java.util.concurrent.LinkedBlockingQueue<>(1024);

    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    /** The request in flight or queued for each (method, file), so a newer one replaces it. */
    private final Map<String, Job> latest = new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicInteger abandoned =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean wedged;
    private volatile Workspace workspace = new Workspace(List.of(Path.of("")));
    private volatile boolean initialized;

    /** One request on its way: answered exactly once, by whoever gets there first. */
    private final class Job {
        final JsonNode id;
        final String key;
        final java.util.concurrent.atomic.AtomicBoolean answered =
                new java.util.concurrent.atomic.AtomicBoolean();
        volatile Future<?> future;
        volatile java.util.concurrent.ScheduledFuture<?> timer;
        volatile boolean running;
        final java.util.concurrent.atomic.AtomicBoolean abandonedCounted =
                new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean();

        Job(JsonNode id, String key) {
            this.id = id;
            this.key = key;
        }
    }

    public Server(Engine engine, long timeoutMillis) {
        this.engine = engine;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Serves until shutdown or the end of the input. Returns the exit status (0 clean, 1 bad frame,
     * 3 wedged).
     */
    public int serve(InputStream in, OutputStream out) throws IOException {
        Thread writer = new Thread(() -> writeLoop(out), "weave-serve-writer");
        writer.setDaemon(true);
        writer.start();
        try {
            while (true) {
                if (wedged) return 3;
                String frame;
                try {
                    frame = Framing.read(in);
                } catch (Framing.BadFrame e) {
                    reply(null, error(RpcError.INVALID_REQUEST, "bad-frame", e.getMessage()));
                    drain();
                    return 1; // the stream cannot be trusted to line up again
                } catch (java.io.EOFException e) {
                    return 0;
                }
                if (frame == null) return 0;
                if (handle(frame)) {
                    drain();
                    return 0;
                }
            }
        } finally {
            pool.shutdownNow();
            clock.shutdownNow();
            writer.interrupt();
        }
    }

    private void writeLoop(OutputStream out) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String next = outbox.take();
                Framing.write(out, next);
            }
        } catch (InterruptedException | IOException e) {
            // ends with the server, or with a client that has gone
        }
    }

    /** Gives the writer a moment to send what is queued (the last reply before an exit). */
    private void drain() {
        long until = System.nanoTime() + 2_000_000_000L;
        while (!outbox.isEmpty() && System.nanoTime() < until) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Handles one message; true when the server should stop. */
    private boolean handle(String frame) throws IOException {
        JsonNode message;
        try {
            message = JSON.readTree(frame);
        } catch (IOException e) {
            reply(null, error(RpcError.PARSE, "parse-error", "not valid JSON: " + e.getMessage()));
            return false;
        }
        if (message == null || !message.isObject() || !message.hasNonNull("method")) {
            reply(
                    idOf(message),
                    error(RpcError.INVALID_REQUEST, "invalid-request", "a request needs a method"));
            return false;
        }
        String method = message.get("method").asText();
        JsonNode id = message.get("id");
        JsonNode params = message.has("params") ? message.get("params") : JSON.createObjectNode();
        switch (method) {
            case "initialize" -> {
                reply(id, initialize(params));
                return false;
            }
            case "loom/ping" -> {
                // answered here, by the reader, so it works however busy the workers are: it is how
                // a client tells
                // "slow" from "stuck"
                ObjectNode pong = JSON.createObjectNode();
                pong.put("busy", pool.getActiveCount());
                pong.put("queued", pool.getQueue().size());
                pong.put("abandoned", abandoned.get());
                if (id != null) reply(id, result(pong));
                return false;
            }
            case "loom/cancel" -> {
                if (params.hasNonNull("id")) cancelled.add(params.get("id").asText());
                if (id != null) reply(id, result(JSON.createObjectNode()));
                return false;
            }
            case "loom/shutdown" -> {
                if (id != null) reply(id, result(JSON.createObjectNode()));
                return true;
            }
            default -> {
                Engine.Method m = engine.methods().get(method);
                if (m == null) {
                    if (id != null)
                        reply(
                                id,
                                error(
                                        RpcError.UNKNOWN_METHOD,
                                        "unknown-method",
                                        "no method " + method));
                    return false;
                }
                if (!initialized && id != null) {
                    // a client that skipped the handshake still gets the default folder
                    initialized = true;
                }
                dispatch(id, method, m, params);
                return false;
            }
        }
    }

    private ObjectNode initialize(JsonNode params) {
        List<Path> roots = new java.util.ArrayList<>();
        if (params.has("roots") && params.get("roots").isArray())
            params.get("roots").forEach(r -> roots.add(Path.of(r.asText())));
        if (roots.isEmpty()) roots.add(Path.of(""));
        workspace = new Workspace(roots);
        initialized = true;
        ObjectNode r = JSON.createObjectNode();
        r.put("protocol", PROTOCOL);
        r.put("weave", engine.version());
        r.set("methods", JSON.valueToTree(engine.methods().keySet().stream().sorted().toList()));
        r.set("roots", JSON.valueToTree(workspace.roots().stream().map(Path::toString).toList()));
        return result(r);
    }

    private void dispatch(JsonNode id, String method, Engine.Method m, JsonNode params) {
        Workspace ws = workspace;
        String idKey = id == null ? null : id.asText();
        String fileKey =
                params.hasNonNull("file") ? method + "|" + params.get("file").asText() : null;
        Job job = new Job(id, fileKey);
        if (fileKey != null) {
            // a newer request for the same file replaces the older: it is answered "cancelled" and
            // its work is interrupted
            Job older = latest.put(fileKey, job);
            if (older != null) supersede(older);
        }
        job.timer =
                clock.schedule(
                        () ->
                                giveUp(
                                        job,
                                        new RpcError(
                                                RpcError.TIMEOUT,
                                                "timeout",
                                                method
                                                        + " took longer than "
                                                        + timeoutMillis
                                                        + " ms")),
                        timeoutMillis,
                        TimeUnit.MILLISECONDS);
        try {
            job.future =
                    pool.submit(
                            () -> {
                                job.running = true;
                                try {
                                    if (idKey != null && cancelled.remove(idKey))
                                        throw new RpcError(
                                                RpcError.CANCELLED,
                                                "cancelled",
                                                "cancelled before it started");
                                    if (job.answered.get())
                                        return; // replaced or timed out while it waited
                                    Cancellation c =
                                            () ->
                                                    job.answered.get()
                                                            || (idKey != null
                                                                    && cancelled.contains(idKey));
                                    JsonNode value = m.call(new Request(method, params, ws, c));
                                    if (idKey != null && cancelled.remove(idKey))
                                        throw new RpcError(
                                                RpcError.CANCELLED,
                                                "cancelled",
                                                "cancelled; the answer was dropped");
                                    ObjectNode envelope =
                                            value instanceof ObjectNode o
                                                    ? o
                                                    : JSON.createObjectNode().set("value", value);
                                    if (params.has("version"))
                                        envelope.set("requestVersion", params.get("version"));
                                    answer(job, result(envelope));
                                } catch (RpcError e) {
                                    answer(job, error(e.code(), e.kind(), e.getMessage()));
                                } catch (Throwable t) {
                                    String msg =
                                            t.getMessage() == null
                                                    ? t.getClass().getSimpleName()
                                                    : t.getMessage();
                                    answer(job, error(RpcError.ENGINE, "engine-failure", msg));
                                } finally {
                                    job.finished.set(true);
                                    if (job.answered.get()
                                            && job.timer != null
                                            && job.timer.isDone()
                                            && abandoned.get() > 0) {
                                        // a worker that was given up on has come back: it counts no
                                        // more
                                        if (job.abandonedCounted.compareAndSet(true, false))
                                            abandoned.decrementAndGet();
                                    }
                                }
                            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // too many waiting: say so at once instead of making the editor wait behind them
            answer(
                    job,
                    error(
                            RpcError.ENGINE,
                            "busy",
                            "the engine is busy with "
                                    + QUEUE
                                    + " other requests; try again in a moment"));
        }
    }

    private void supersede(Job older) {
        giveUp(
                older,
                new RpcError(
                        RpcError.CANCELLED,
                        "cancelled",
                        "replaced by a newer request for the same file"));
    }

    /** Answers a job that will not finish in time (or is replaced), and frees what it holds. */
    private void giveUp(Job job, RpcError why) {
        if (!answer(job, error(why.code(), why.kind(), why.getMessage()))) return;
        Future<?> f = job.future;
        if (f != null)
            f.cancel(true); // interrupts the worker if it is running; removes it from the queue if
        // not
        if (job.running && !job.finished.get()) {
            // the worker may not stop (code that ignores interrupts): count it, and if too many are
            // stuck, exit to be restarted
            if (job.abandonedCounted.compareAndSet(false, true)
                    && abandoned.incrementAndGet() > MAX_ABANDONED) wedged = true;
        }
    }

    /** Sends the answer once; false when the job was already answered. */
    private boolean answer(Job job, ObjectNode body) {
        if (!job.answered.compareAndSet(false, true)) return false;
        if (job.timer != null) job.timer.cancel(false);
        if (job.key != null) latest.remove(job.key, job);
        if (job.id != null) reply(job.id, body);
        return true;
    }

    // ---- replies

    private static JsonNode idOf(JsonNode message) {
        return message != null && message.isObject() ? message.get("id") : null;
    }

    private static ObjectNode result(JsonNode value) {
        ObjectNode r = JSON.createObjectNode();
        r.set("result", value);
        return r;
    }

    private static ObjectNode error(int code, String kind, String message) {
        ObjectNode e = JSON.createObjectNode();
        e.put("code", code);
        e.put("message", message);
        e.putObject("data").put("kind", kind);
        ObjectNode r = JSON.createObjectNode();
        r.set("error", e);
        return r;
    }

    private void reply(JsonNode id, ObjectNode body) {
        ObjectNode msg = JSON.createObjectNode();
        msg.put("jsonrpc", "2.0");
        if (id == null || id.isNull()) msg.putNull("id");
        else msg.set("id", id);
        body.fields().forEachRemaining(f -> msg.set(f.getKey(), f.getValue()));
        // never block a worker or the reader on the client: if it has stopped reading, replies are
        // dropped and the client
        // will see its own timeouts and restart the engine
        outbox.offer(msg.toString());
    }
}
