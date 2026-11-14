package io.github.llm4j.tools.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * A small Chrome DevTools Protocol client on {@link java.net.http.WebSocket}: commands with an id
 * and an optional session, answered by id; events handed to one listener. No library.
 */
final class Cdp implements Channel {

    static final ObjectMapper JSON = new ObjectMapper();
    /** The largest message read: a screenshot is the largest the browser sends. */
    static final int MAX_MESSAGE = 64 * 1024 * 1024;

    private final WebSocket socket;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonNode>> waiting = new ConcurrentHashMap<>();

    Cdp(WebSocket socket) {
        this.socket = socket;
    }

    /**
     * Connects to {@code url} (the browser's own, on loopback); {@code events} gets each event's
     * method, its params and session id joined as {@code params.sessionId}.
     */
    static Cdp connect(URI url, Duration timeout, BiConsumer<String, JsonNode> events) throws IOException {
        Listener listener = new Listener(events);
        try {
            WebSocket ws =
                    HttpClient.newBuilder().connectTimeout(timeout).build()
                            .newWebSocketBuilder()
                            .connectTimeout(timeout)
                            .buildAsync(url, listener)
                            .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            Cdp cdp = new Cdp(ws);
            listener.cdp = cdp;
            return cdp;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while connecting to the browser", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("could not connect to the browser: " + e.getMessage(), e);
        }
    }

    @Override
    public JsonNode call(String method, ObjectNode params, String session, Duration timeout) throws IOException {
        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        int id = send(method, params, session, answer);
        try {
            JsonNode r = answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (r.has("error")) throw new IOException(method + ": " + r.get("error").path("message").asText());
            return r.path("result");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for " + method, e);
        } catch (ExecutionException e) {
            throw new IOException(method + ": " + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            throw new IOException(method + " took longer than " + timeout.toSeconds() + "s");
        } finally {
            waiting.remove(id);
        }
    }

    /** Sends a command without waiting: for answering a paused request from the event thread. */
    @Override
    public void post(String method, ObjectNode params, String session) {
        send(method, params, session, null);
    }

    private int send(String method, ObjectNode params, String session, CompletableFuture<JsonNode> answer) {
        int id = ids.incrementAndGet();
        if (answer != null) waiting.put(id, answer);
        ObjectNode m = JSON.createObjectNode();
        m.put("id", id);
        m.put("method", method);
        m.set("params", params == null ? JSON.createObjectNode() : params);
        if (session != null) m.put("sessionId", session);
        synchronized (socket) {
            socket.sendText(m.toString(), true).join();
        }
        return id;
    }

    static ObjectNode params() {
        return JSON.createObjectNode();
    }

    void received(String text, BiConsumer<String, JsonNode> events) {
        JsonNode m;
        try {
            m = JSON.readTree(text);
        } catch (IOException e) {
            return; // not a protocol message
        }
        if (m.has("id")) {
            CompletableFuture<JsonNode> f = waiting.get(m.get("id").asInt());
            if (f != null) f.complete(m);
            return;
        }
        JsonNode params = m.path("params");
        if (params instanceof ObjectNode o && m.has("sessionId")) o.put("sessionId", m.get("sessionId").asText());
        events.accept(m.path("method").asText(), params);
    }

    @Override
    public void close() {
        waiting.values().forEach(f -> f.completeExceptionally(new IOException("the browser connection closed")));
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            socket.abort();
        }
    }

    /** Joins fragments into messages and hands them on. */
    static final class Listener implements WebSocket.Listener {
        private final BiConsumer<String, JsonNode> events;
        private final StringBuilder partial = new StringBuilder();
        volatile Cdp cdp;

        Listener(BiConsumer<String, JsonNode> events) {
            // package-private so a test can feed it fragments
            this.events = events;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (partial.length() > MAX_MESSAGE) partial.setLength(0);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                Cdp c = cdp;
                if (c != null) c.received(text, events);
            }
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            Cdp c = cdp;
            if (c != null) c.waiting.values().forEach(f -> f.completeExceptionally(error));
        }
    }
}
