package io.github.llm4j.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.llm4j.a2a.testkit.OfficialDefinition;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Mounts our {@link A2AHandler} on a loopback HTTP server, the way a consumer mounts it in their
 * own stack: the host authenticates (here the Authorization header is the scope key), parses the
 * request with the helper, calls the handler and writes the answer. It checks every message it
 * sends against the official definition.
 */
final class LoopbackA2AServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long HOST_WAIT_SECONDS = 5;

    private final HttpServer server;
    final List<String> invalidOutput = Collections.synchronizedList(new ArrayList<>());

    LoopbackA2AServer(A2AHandler handler, AgentCardBuilder.Published card) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/.well-known/agent-card.json",
                ex -> {
                    byte[] body = card.json().getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("ETag", card.etag());
                    ex.getResponseHeaders().add("Cache-Control", "max-age=60");
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().write(body);
                    ex.close();
                });
        server.createContext("/rpc", ex -> serve(handler, ex));
        server.start();
    }

    String loopbackBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void serve(A2AHandler handler, HttpExchange ex) throws IOException {
        String scope = ex.getRequestHeaders().getFirst("Authorization");
        if (scope == null || scope.isBlank()) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        String request = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode id = JSON.nullNode();
        String response;
        try {
            A2AJsonRpc10.Call call = A2AJsonRpc10.parseRequest(request);
            id = call.id();
            // the host decides how long to wait for a send that must wait; the handler never blocks
            A2AResponse answer =
                    handler.handle(new CallerIdentity(scope), call.request())
                            .toCompletableFuture()
                            .get(HOST_WAIT_SECONDS, TimeUnit.SECONDS);
            response = A2AJsonRpc10.writeResponse(id, call.request(), answer);
            validate(call.request(), response);
        } catch (java.util.concurrent.ExecutionException e) {
            response =
                    A2AJsonRpc10.writeError(
                            id,
                            e.getCause() instanceof A2AException a
                                    ? a
                                    : new A2AException("internal"));
        } catch (A2AException e) {
            response = A2AJsonRpc10.writeError(id, e);
        } catch (Exception e) {
            response = A2AJsonRpc10.writeError(id, new A2AException("internal"));
        }
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private void validate(A2ARequest request, String response) throws IOException {
        String type =
                request instanceof A2ARequest.Send
                        ? "SendMessageResponse"
                        : request instanceof A2ARequest.ListTasks ? "ListTasksResponse" : "Task";
        List<String> problems =
                OfficialDefinition.problems(type, JSON.readTree(response).get("result").toString());
        if (!problems.isEmpty()) {
            invalidOutput.add(type + ": " + problems);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
