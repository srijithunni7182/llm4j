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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

        /** The unsaved text the client sent for {@code params.text}, keyed by the file. */
        public Map<Path, String> texts() throws RpcError {
            if (!params.hasNonNull("text")) return Map.of();
            return Map.of(file(), params.get("text").asText());
        }
    }

    /** Whether the client has withdrawn the request. */
    public interface Cancellation {
        boolean cancelled();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Engine engine;
    private final long timeoutMillis;
    private final ExecutorService pool =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r, "weave-serve-worker");
                        t.setDaemon(true);
                        return t;
                    });
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final Object writeLock = new Object();
    private volatile Workspace workspace = new Workspace(List.of(Path.of("")));
    private volatile boolean initialized;

    public Server(Engine engine, long timeoutMillis) {
        this.engine = engine;
        this.timeoutMillis = timeoutMillis;
    }

    /** Serves until shutdown or the end of the input. Returns the exit status (0 clean). */
    public int serve(InputStream in, OutputStream out) throws IOException {
        try {
            while (true) {
                String frame;
                try {
                    frame = Framing.read(in);
                } catch (Framing.BadFrame e) {
                    reply(out, null, error(RpcError.INVALID_REQUEST, "bad-frame", e.getMessage()));
                    return 1; // the stream cannot be trusted to line up again
                } catch (java.io.EOFException e) {
                    return 0;
                }
                if (frame == null) return 0;
                if (handle(frame, out)) return 0;
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Handles one message; true when the server should stop. */
    private boolean handle(String frame, OutputStream out) throws IOException {
        JsonNode message;
        try {
            message = JSON.readTree(frame);
        } catch (IOException e) {
            reply(
                    out,
                    null,
                    error(RpcError.PARSE, "parse-error", "not valid JSON: " + e.getMessage()));
            return false;
        }
        if (message == null || !message.isObject() || !message.hasNonNull("method")) {
            reply(
                    out,
                    idOf(message),
                    error(RpcError.INVALID_REQUEST, "invalid-request", "a request needs a method"));
            return false;
        }
        String method = message.get("method").asText();
        JsonNode id = message.get("id");
        JsonNode params = message.has("params") ? message.get("params") : JSON.createObjectNode();
        switch (method) {
            case "initialize" -> {
                reply(out, id, initialize(params));
                return false;
            }
            case "loom/cancel" -> {
                if (params.hasNonNull("id")) cancelled.add(params.get("id").asText());
                if (id != null) reply(out, id, result(JSON.createObjectNode()));
                return false;
            }
            case "loom/shutdown" -> {
                if (id != null) reply(out, id, result(JSON.createObjectNode()));
                return true;
            }
            default -> {
                Engine.Method m = engine.methods().get(method);
                if (m == null) {
                    if (id != null)
                        reply(
                                out,
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
                dispatch(out, id, method, m, params);
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

    private void dispatch(
            OutputStream out, JsonNode id, String method, Engine.Method m, JsonNode params) {
        String key = id == null ? null : id.asText();
        Workspace ws = workspace;
        pool.submit(
                () -> {
                    ObjectNode response;
                    try {
                        if (key != null && cancelled.remove(key))
                            throw new RpcError(
                                    RpcError.CANCELLED, "cancelled", "cancelled before it started");
                        Cancellation c = () -> key != null && cancelled.contains(key);
                        Future<JsonNode> work =
                                pool.submit(() -> m.call(new Request(method, params, ws, c)));
                        JsonNode value;
                        try {
                            value = work.get(timeoutMillis, TimeUnit.MILLISECONDS);
                        } catch (TimeoutException e) {
                            work.cancel(true);
                            throw new RpcError(
                                    RpcError.TIMEOUT,
                                    "timeout",
                                    method + " took longer than " + timeoutMillis + " ms");
                        } catch (java.util.concurrent.ExecutionException e) {
                            Throwable cause = e.getCause();
                            if (cause instanceof RpcError r) throw r;
                            throw new RpcError(
                                    RpcError.ENGINE,
                                    "engine-failure",
                                    cause == null ? "failed" : String.valueOf(cause.getMessage()));
                        }
                        if (key != null && cancelled.remove(key))
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
                        response = result(envelope);
                    } catch (RpcError e) {
                        response = error(e.code(), e.kind(), e.getMessage());
                    } catch (Throwable t) {
                        response =
                                error(
                                        RpcError.INTERNAL,
                                        "internal",
                                        t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                    try {
                        reply(out, id, response);
                    } catch (IOException ignored) {
                        // the client has gone; the read loop will see the end of input
                    }
                });
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

    private void reply(OutputStream out, JsonNode id, ObjectNode body) throws IOException {
        ObjectNode msg = JSON.createObjectNode();
        msg.put("jsonrpc", "2.0");
        if (id == null || id.isNull()) msg.putNull("id");
        else msg.set("id", id);
        body.fields().forEachRemaining(f -> msg.set(f.getKey(), f.getValue()));
        synchronized (writeLock) {
            Framing.write(out, msg.toString());
        }
    }
}
