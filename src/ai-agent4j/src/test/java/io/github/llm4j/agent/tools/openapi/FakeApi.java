package io.github.llm4j.agent.tools.openapi;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A downstream API on localhost for tests: it records every request and answers from a queue of
 * replies (200 {@code {}} when the queue is empty), or by a function of the request. The only
 * network these tests touch.
 */
final class FakeApi implements AutoCloseable {

    record Request(String method, String target, Map<String, String> headers, String body) {
        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    record Reply(int status, String body, Map<String, String> headers) {
        static Reply ok(String body) {
            return new Reply(200, body, Map.of());
        }

        static Reply status(int status, String body) {
            return new Reply(status, body, Map.of());
        }

        static Reply redirect(String location) {
            return new Reply(302, "", Map.of("Location", location));
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Deque<Reply> queue = new ArrayDeque<>();
    private volatile Function<Request, Reply> responder;

    FakeApi() {
        try {
            server =
                    HttpServer.create(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext(
                "/",
                exchange -> {
                    Request request =
                            new Request(
                                    exchange.getRequestMethod(),
                                    exchange.getRequestURI().toString(),
                                    lowerCased(exchange.getRequestHeaders()),
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                    requests.add(request);
                    Reply reply = next(request);
                    reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
                    byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(
                            reply.status(), bytes.length == 0 ? -1 : bytes.length);
                    if (bytes.length > 0) exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
    }

    private static Map<String, String> lowerCased(com.sun.net.httpserver.Headers headers) {
        Map<String, String> out = new java.util.HashMap<>();
        headers.forEach(
                (k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT), String.join(",", v)));
        return out;
    }

    private synchronized Reply next(Request request) {
        if (responder != null) return responder.apply(request);
        Reply reply = queue.poll();
        return reply != null ? reply : Reply.ok("{}");
    }

    FakeApi replies(Reply... replies) {
        synchronized (this) {
            queue.addAll(List.of(replies));
        }
        return this;
    }

    FakeApi respondWith(Function<Request, Reply> responder) {
        this.responder = responder;
        return this;
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    List<Request> requests() {
        return new ArrayList<>(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
