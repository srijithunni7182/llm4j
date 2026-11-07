package io.github.llm4j.a2a;

import io.github.llm4j.secret.SecretRef;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A small transport for the tests: it speaks JSON-RPC 1.0 through {@link A2AJsonRpc10} and routes
 * the https host the card names to a loopback port. Not the starter, and not for production: it
 * does no address validation, no redirects policy and no credential handling beyond what the tests
 * need.
 */
final class HttpTestTransport implements A2ATransport {

    private static final int MAX_RESPONSE_BYTES = 1_000_000;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String publicBase;
    private final String loopbackBase;
    private final Set<String> extensions = new LinkedHashSet<>();
    private final AtomicLong ids = new AtomicLong();

    HttpTestTransport(String publicBase, String loopbackBase) {
        this.publicBase = publicBase;
        this.loopbackBase = loopbackBase;
    }

    HttpTestTransport extension(String uri) {
        extensions.add(uri);
        return this;
    }

    private String route(String url) {
        if (!url.startsWith(publicBase)) {
            throw new A2AException("the test transport only routes " + publicBase);
        }
        return loopbackBase + url.substring(publicBase.length());
    }

    @Override
    public AgentCardView fetchCard(String address) {
        return A2AJsonRpc10.parseCard(
                call(HttpRequest.newBuilder(URI.create(route(address))).GET().build()));
    }

    private String rpc(AgentCardView.Endpoint endpoint, A2AJsonRpc10.Request request) {
        var builder =
                HttpRequest.newBuilder(URI.create(route(endpoint.url())))
                        .header("Content-Type", "application/json");
        request.headers().forEach(builder::header);
        if (!extensions.isEmpty()) {
            builder.header("A2A-Extensions", String.join(",", extensions));
        }
        return call(builder.POST(HttpRequest.BodyPublishers.ofString(request.body())).build());
    }

    private String call(HttpRequest request) {
        try {
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] body = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES) {
                    throw new A2AException(
                            "the response is larger than "
                                    + MAX_RESPONSE_BYTES
                                    + " bytes; refused");
                }
                if (response.statusCode() == 401) {
                    throw new A2AException("the remote refused the credentials (401)");
                }
                return new String(body, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new A2AException("the call failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new A2AException("interrupted", e);
        }
    }

    @Override
    public SendResult send(
            AgentCardView.Endpoint endpoint,
            SecretRef credential,
            A2AMessage message,
            SendOptions options) {
        return A2AJsonRpc10.parseSendResponse(
                rpc(
                        endpoint,
                        A2AJsonRpc10.sendMessage(
                                ids.incrementAndGet(), message, options, endpoint.tenant())));
    }

    @Override
    public RemoteTask get(AgentCardView.Endpoint endpoint, SecretRef credential, String taskId) {
        return A2AJsonRpc10.parseGetTaskResponse(
                rpc(
                        endpoint,
                        A2AJsonRpc10.getTask(
                                ids.incrementAndGet(), taskId, 10, endpoint.tenant())));
    }

    @Override
    public RemoteTask cancel(AgentCardView.Endpoint endpoint, SecretRef credential, String taskId) {
        return A2AJsonRpc10.parseGetTaskResponse(
                rpc(
                        endpoint,
                        A2AJsonRpc10.cancelTask(ids.incrementAndGet(), taskId, endpoint.tenant())));
    }

    @Override
    public List<RemoteTask> recentTasks(
            AgentCardView.Endpoint endpoint,
            SecretRef credential,
            Instant since,
            int historyLimit) {
        return A2AJsonRpc10.parseListTasksResponse(
                rpc(
                        endpoint,
                        A2AJsonRpc10.listTasks(
                                ids.incrementAndGet(),
                                since,
                                historyLimit,
                                50,
                                endpoint.tenant())));
    }

    @Override
    public boolean supports(AgentCardView.Endpoint endpoint) {
        return A2AJsonRpc10.supports(endpoint);
    }

    @Override
    public Set<String> supportedExtensions() {
        return Set.copyOf(extensions);
    }
}
