package io.github.llm4j.examples.expensedesk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The desk over HTTP, on the JDK's own server: one page, and a small JSON API the page calls.
 *
 * <ul>
 *   <li>{@code GET /api/state}: the claims, the payments and the house rules
 *   <li>{@code GET /api/architecture}: the script, its graph and its security audit
 *   <li>{@code POST /api/claims}: hand in a claim ({@code employee}, {@code text})
 *   <li>{@code POST /api/claims/<id>/decision}: approve or decline ({@code approve}, {@code by})
 * </ul>
 *
 * <p>A request body is read into an explicit type and is bounded; a write must be JSON, which a
 * form on another site cannot send.
 */
public final class DeskServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int WORKERS = 4;
    private static final Pattern DECISION = Pattern.compile("/api/claims/([A-Za-z0-9-]{1,100})/decision");
    private static final String JSON_TYPE = "application/json";

    private record NewClaim(String employee, String text) {}

    private record Decision(boolean approve, String by) {}

    /** A request the server refuses, with the HTTP status to refuse it with. */
    private static final class Refused extends RuntimeException {
        private final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final Desk desk;
    private final String mode;
    private final String page;
    private final String renderer;
    private final HttpServer server;
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKERS);

    /**
     * Starts serving.
     *
     * @param address where to listen; port 0 picks a free one
     * @param mode what the page says about the models ("offline" or "live")
     * @param renderer the source of the Loom graph renderer, or empty to show the graph as a list
     */
    public DeskServer(Desk desk, InetSocketAddress address, String mode, String renderer) {
        this.desk = Objects.requireNonNull(desk, "desk");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.page = resource("/web/index.html");
        try {
            this.server = HttpServer.create(Objects.requireNonNull(address, "address"), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("the desk could not listen on " + address, e);
        }
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.start();
    }

    /** The port the server really listens on. */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdown();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                route(exchange);
            } catch (Refused e) {
                send(exchange, e.status, JSON_TYPE, json(Map.of("error", e.getMessage())));
            } catch (IllegalArgumentException e) {
                send(exchange, 400, JSON_TYPE, json(Map.of("error", e.getMessage())));
            } catch (IllegalStateException e) {
                send(exchange, 409, JSON_TYPE, json(Map.of("error", e.getMessage())));
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        boolean get = exchange.getRequestMethod().equals("GET");
        boolean post = exchange.getRequestMethod().equals("POST");
        Matcher decision = DECISION.matcher(path);
        if (get && path.equals("/")) {
            send(exchange, 200, "text/html; charset=utf-8", page);
        } else if (get && path.equals("/graph-render.js")) {
            send(exchange, 200, "text/javascript; charset=utf-8", renderer);
        } else if (get && path.equals("/api/state")) {
            send(exchange, 200, JSON_TYPE, json(state()));
        } else if (get && path.equals("/api/architecture")) {
            send(exchange, 200, JSON_TYPE, architecture());
        } else if (post && path.equals("/api/claims")) {
            NewClaim claim = body(exchange, NewClaim.class);
            send(exchange, 202, JSON_TYPE, json(Map.of("id", desk.submit(claim.employee(), claim.text()).id())));
        } else if (post && decision.matches()) {
            Decision made = body(exchange, Decision.class);
            desk.decide(decision.group(1), made.approve(), made.by());
            send(exchange, 202, JSON_TYPE, json(Map.of("recorded", true)));
        } else {
            throw new Refused(get || post ? 404 : 405, "there is nothing at " + exchange.getRequestMethod() + " " + path);
        }
    }

    private Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("mode", mode);
        state.put("claims", desk.claims());
        state.put("payments", desk.payments());
        state.put("policy", ExpensePolicy.describe());
        return state;
    }

    /** The graph and the audit are JSON already, so they are placed in the answer as they are. */
    private String architecture() {
        Desk.Architecture a = desk.architecture();
        return "{\"script\":" + json(a.script()) + ",\"graph\":" + a.graphJson() + ",\"audit\":" + a.auditJson() + "}";
    }

    private static <T> T body(HttpExchange exchange, Class<T> type) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith(JSON_TYPE)) {
            throw new Refused(415, "send the request as application/json");
        }
        byte[] bytes;
        try (InputStream in = exchange.getRequestBody()) {
            bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new Refused(413, "the request is larger than " + MAX_BODY_BYTES + " bytes");
        }
        try {
            T value = JSON.readValue(bytes, type);
            if (value == null) throw new Refused(400, "the request has no JSON object");
            return value;
        } catch (JsonProcessingException e) {
            throw new Refused(400, "the request is not the JSON this address takes");
        }
    }

    private static void send(HttpExchange exchange, int status, String type, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the answer could not be written as JSON", e);
        }
    }

    private static String resource(String name) {
        try (InputStream in = DeskServer.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException(name + " is missing from the class path");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(name + " could not be read", e);
        }
    }
}
