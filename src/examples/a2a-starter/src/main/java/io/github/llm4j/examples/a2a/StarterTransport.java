package io.github.llm4j.examples.a2a;

import io.github.llm4j.a2a.A2AException;
import io.github.llm4j.a2a.A2AJsonRpc10;
import io.github.llm4j.a2a.A2AMessage;
import io.github.llm4j.a2a.A2ATransport;
import io.github.llm4j.a2a.AgentCardView;
import io.github.llm4j.a2a.RemoteTask;
import io.github.llm4j.a2a.SendOptions;
import io.github.llm4j.a2a.SendResult;
import io.github.llm4j.secret.SecretRef;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How the code around llm4j reaches a remote agent. llm4j gives you {@link A2AJsonRpc10} to write
 * and read the messages; everything else on this page is yours, and this class shows where.
 *
 * <p><b>This is a starter, not production code.</b> The places that need a decision of yours are
 * marked {@code YOUR DECISION}.
 */
public final class StarterTransport implements A2ATransport {

    /** Bound the size of what a remote can make you read. */
    private static final int MAX_RESPONSE_BYTES = 1_000_000;

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    // YOUR DECISION: redirects. Never follow one blindly: a redirect can leave the
                    // host the card named, which is what the endpoint guard exists to prevent.
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    private final AtomicLong ids = new AtomicLong();

    @Override
    public AgentCardView fetchCard(String address) {
        requireSafe(URI.create(address));
        return A2AJsonRpc10.parseCard(
                call(HttpRequest.newBuilder(URI.create(address)).timeout(CALL_TIMEOUT).GET().build()));
    }

    /**
     * YOUR DECISION: address validation. A remote chooses the addresses in its card, so refuse what
     * should never be reached from your network: loopback, private ranges and cloud metadata
     * addresses. {@code ai-agent4j-tools} has {@code NetPolicy} for exactly this; a minimal check is
     * shown so the starter has no extra dependency.
     */
    private static void requireSafe(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null)
            throw new A2AException("only https addresses are called: " + uri);
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost()))
                if (address.isLoopbackAddress()
                        || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress())
                    throw new A2AException("the address of " + uri.getHost() + " is not a public one; refused");
        } catch (java.net.UnknownHostException e) {
            throw new A2AException("the host " + uri.getHost() + " does not resolve", e);
        }
    }

    private String rpc(AgentCardView.Endpoint endpoint, SecretRef credential, A2AJsonRpc10.Request request) {
        URI uri = URI.create(endpoint.url());
        requireSafe(uri);
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(uri).timeout(CALL_TIMEOUT).header("Content-Type", "application/json");
        if (credential != null) {
            // resolved per request, and only for the host the secret is bound to
            builder.header("Authorization", credential.resolveFor(uri.getHost()));
        }
        request.headers().forEach(builder::header);
        return call(builder.POST(HttpRequest.BodyPublishers.ofString(request.body())).build());
    }

    private String call(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] body = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES)
                    throw new A2AException("the response is larger than " + MAX_RESPONSE_BYTES + " bytes; refused");
                if (response.statusCode() == 401 || response.statusCode() == 403)
                    throw new A2AException("the remote refused the credentials (" + response.statusCode() + ")");
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
    public SendResult send(AgentCardView.Endpoint endpoint, SecretRef credential, A2AMessage message, SendOptions options) {
        return A2AJsonRpc10.parseSendResponse(
                rpc(endpoint, credential, A2AJsonRpc10.sendMessage(ids.incrementAndGet(), message, options, endpoint.tenant())));
    }

    @Override
    public RemoteTask get(AgentCardView.Endpoint endpoint, SecretRef credential, String taskId) {
        return A2AJsonRpc10.parseGetTaskResponse(
                rpc(endpoint, credential, A2AJsonRpc10.getTask(ids.incrementAndGet(), taskId, 10, endpoint.tenant())));
    }

    @Override
    public RemoteTask cancel(AgentCardView.Endpoint endpoint, SecretRef credential, String taskId) {
        return A2AJsonRpc10.parseGetTaskResponse(
                rpc(endpoint, credential, A2AJsonRpc10.cancelTask(ids.incrementAndGet(), taskId, endpoint.tenant())));
    }

    @Override
    public List<RemoteTask> recentTasks(AgentCardView.Endpoint endpoint, SecretRef credential, Instant since, int historyLimit) {
        return A2AJsonRpc10.parseListTasksResponse(
                rpc(endpoint, credential, A2AJsonRpc10.listTasks(ids.incrementAndGet(), since, historyLimit, 50, endpoint.tenant())));
    }

    @Override
    public boolean supports(AgentCardView.Endpoint endpoint) {
        return A2AJsonRpc10.supports(endpoint);
    }

    /** YOUR DECISION: extensions you understand. The runtime refuses a card that requires any other. */
    @Override
    public Set<String> supportedExtensions() {
        return Set.of();
    }
}
