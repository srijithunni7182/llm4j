package io.github.llm4j.loom.install;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Fetches from Maven Central over https and nowhere else: every address, and every redirect, must
 * pass {@link ToolsFetch#allowed}. A download is bounded in size and in time.
 */
public final class MavenCentralFetcher implements ToolsFetch.Fetcher {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    @Override
    public OptionalLong size(URI uri) throws IOException {
        HttpResponse<Void> response = send(HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) return OptionalLong.empty();
        return response.headers().firstValueAsLong("content-length");
    }

    @Override
    public Optional<byte[]> get(URI uri, long maxBytes) throws IOException {
        HttpResponse<InputStream> response =
                send(HttpRequest.newBuilder(uri).GET(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() == 404) return Optional.empty();
            if (response.statusCode() != 200)
                throw new IOException(uri + " answered HTTP " + response.statusCode());
            byte[] bytes = in.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE));
            if (bytes.length > maxBytes)
                throw new IOException(uri + " is larger than the " + maxBytes + " byte limit");
            return Optional.of(bytes);
        }
    }

    private <T> HttpResponse<T> send(
            HttpRequest.Builder request, HttpResponse.BodyHandler<T> handler) throws IOException {
        HttpRequest built = request.timeout(TIMEOUT).build();
        if (!ToolsFetch.allowed(built.uri()))
            throw new IOException("refusing to contact " + built.uri());
        try {
            HttpResponse<T> response = client.send(built, handler);
            if (response.statusCode() / 100 == 3) {
                // Central redirects only within itself; follow one hop, still inside the allow-list
                String to = response.headers().firstValue("location").orElse("");
                URI next = built.uri().resolve(to);
                if (!ToolsFetch.allowed(next))
                    throw new IOException(built.uri() + " redirected to " + next + ", which is not allowed");
                return client.send(HttpRequest.newBuilder(next).method(built.method(), built.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())).timeout(TIMEOUT).build(), handler);
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
