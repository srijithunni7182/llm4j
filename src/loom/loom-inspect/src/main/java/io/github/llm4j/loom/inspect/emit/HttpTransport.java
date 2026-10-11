package io.github.llm4j.loom.inspect.emit;

import io.github.llm4j.loom.inspect.wire.Signer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import javax.net.ssl.SSLContext;

/**
 * The real transport: HTTPS (or loopback HTTP) to exactly the URL it was given, no redirects followed, every request signed with the
 * client's key. The key lives here and nowhere else; it never appears in a message or a log line, and {@code toString} omits it.
 */
public final class HttpTransport implements Transport {

    private final HttpClient http;
    private final URI base;
    private final String client;
    private final String key;
    private final Clock clock;

    public HttpTransport(URI base, SSLContext tls, String client, String key, Clock clock) {
        HttpClient.Builder b = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5));
        if (tls != null) b.sslContext(tls);
        this.http = b.build();
        this.base = base;
        this.client = client;
        this.key = key;
        this.clock = clock;
    }

    @Override
    public Reply post(String path, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String root = base.toString().replaceAll("/+$", "");
        HttpRequest req = HttpRequest.newBuilder(URI.create(root + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header(Signer.CLIENT_HEADER, client)
                .header(Signer.HEADER, Signer.sign(key, clock.instant(), bytes))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            long retry = r.headers().firstValue("Retry-After").map(HttpTransport::seconds).orElse(0L);
            return new Reply(r.statusCode(), r.body(), retry);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static long seconds(String v) {
        try {
            return Math.max(0, Math.min(300, Long.parseLong(v.trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public String toString() {
        return "HttpTransport[" + base + " as " + client + "]";
    }
}
