package io.github.llm4j.examples.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.llm4j.a2a.A2AException;
import io.github.llm4j.a2a.A2AHandler;
import io.github.llm4j.a2a.A2AJsonRpc10;
import io.github.llm4j.a2a.A2AResponse;
import io.github.llm4j.a2a.AgentCardBuilder;
import io.github.llm4j.a2a.CallerIdentity;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Mounts the handler in your own stack: serve the card, authenticate, parse the request with the
 * helper, call the handler, write the answer. llm4j runs no server, so all of this is yours.
 *
 * <p><b>A starter.</b> Authentication here is a placeholder that treats a bearer token as the scope
 * key; it proves nothing. Put TLS in front of it, check who is calling, and map the caller to a
 * scope key you trust.
 */
public final class StarterServer implements AutoCloseable {

    /** YOUR DECISION: how long a request that must wait is held. The handler itself never blocks. */
    private static final long MOST_SECONDS_TO_HOLD = 30;

    private final HttpServer server;
    private final A2AHandler handler;

    /**
     * @param port the port to listen on
     * @param handler the handler over your runners
     * @param card the card to serve, built by {@code ExposedCard.of} or {@code AgentCardBuilder}
     */
    public StarterServer(int port, A2AHandler handler, AgentCardBuilder.Published card) throws IOException {
        this.handler = handler;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/.well-known/agent-card.json", exchange -> card(exchange, card));
        server.createContext("/a2a", this::rpc);
    }

    /** Starts listening. */
    public void start() {
        server.start();
    }

    private void card(HttpExchange exchange, AgentCardBuilder.Published card) throws IOException {
        byte[] body = card.json().getBytes(StandardCharsets.UTF_8);
        // YOUR DECISION: caching. A client asks for the card often; say how long it may keep it.
        exchange.getResponseHeaders().add("ETag", card.etag());
        exchange.getResponseHeaders().add("Cache-Control", "max-age=300");
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void rpc(HttpExchange exchange) throws IOException {
        String scope = authenticate(exchange);
        if (scope == null) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        String request = new String(exchange.getRequestBody().readNBytes(1_000_000), StandardCharsets.UTF_8);
        // read the id first, so an error for a request that cannot be parsed still answers that request
        JsonNode id = A2AJsonRpc10.idOf(request);
        String response;
        try {
            // YOUR DECISION: the versions you speak. The helper refuses anything but 1.0, and a request
            // with no A2A-Version header means 0.3 (the specification's rule), so it is refused too.
            A2AJsonRpc10.requireSupportedVersion(exchange.getRequestHeaders().getFirst("A2A-Version"));
            A2AJsonRpc10.Call call = A2AJsonRpc10.parseRequest(request);
            A2AResponse answer =
                    handler.handle(new CallerIdentity(scope), call.request())
                            .toCompletableFuture()
                            .get(MOST_SECONDS_TO_HOLD, TimeUnit.SECONDS);
            response = A2AJsonRpc10.writeResponse(id, call.request(), answer);
        } catch (A2AException e) {
            response = A2AJsonRpc10.writeError(id, e);
        } catch (ExecutionException e) {
            response = A2AJsonRpc10.writeError(id, e.getCause() instanceof A2AException a ? a : new A2AException("internal error"));
        } catch (TimeoutException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            response = A2AJsonRpc10.writeError(id, new A2AException("the agent took too long; ask for the task again"));
        }
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /**
     * YOUR DECISION: authentication. This starter reads a bearer token and uses it as the scope
     * key, which is only a placeholder: replace it with a check of who is calling.
     */
    private static String authenticate(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() <= "Bearer ".length()) return null;
        return header.substring("Bearer ".length());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
