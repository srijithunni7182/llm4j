package io.github.llm4j.eval.live;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * A local reverse proxy to one vendor that remembers every exchange, so a provider pointed at it
 * behaves exactly as it does against the vendor while the smoke run keeps the real wire bytes (to
 * commit as fixtures, after {@link Scrub}). The provider needs no hook for it: it is given this
 * proxy's address as its base URL, and the key still travels in its own headers.
 */
final class RecordingProxy implements AutoCloseable {

    /** One request and the vendor's whole answer. The key is never part of it. */
    record Exchange(
            String method,
            String path,
            String requestBody,
            int status,
            String contentType,
            String responseBody) {}

    private static final Set<String> FORWARDED =
            Set.of(
                    "authorization",
                    "x-api-key",
                    "x-goog-api-key",
                    "anthropic-version",
                    "anthropic-beta",
                    "content-type",
                    "accept",
                    "api-subscription-key");

    private final MockWebServer server = new MockWebServer();
    private final List<Exchange> exchanges = new ArrayList<>();
    private final OkHttpClient upstream =
            new OkHttpClient.Builder()
                    .callTimeout(Duration.ofSeconds(180))
                    .readTimeout(Duration.ofSeconds(180))
                    .build();

    /**
     * @param origin the vendor's scheme and host, for example {@code https://api.openai.com}
     */
    RecordingProxy(String origin) throws IOException {
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest r) {
                        return forward(origin, r);
                    }
                });
        server.start();
    }

    /**
     * The proxy's address; append the vendor's path prefix ({@code /v1}) to get a provider base
     * URL.
     */
    String url() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    private MockResponse forward(String origin, RecordedRequest r) {
        String body = r.getBody().readUtf8();
        Headers.Builder headers = new Headers.Builder();
        for (String name : r.getHeaders().names()) {
            if (FORWARDED.contains(name.toLowerCase(Locale.ROOT)))
                headers.add(name, r.getHeader(name));
        }
        Request.Builder req =
                new Request.Builder().url(origin + r.getPath()).headers(headers.build());
        if (r.getMethod().equals("POST")) {
            String type = r.getHeader("Content-Type");
            req.post(RequestBody.create(body, type == null ? null : MediaType.parse(type)));
        }
        try (Response response = upstream.newCall(req.build()).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            String type = response.header("Content-Type");
            synchronized (exchanges) {
                exchanges.add(
                        new Exchange(
                                r.getMethod(), r.getPath(), body, response.code(), type, text));
            }
            MockResponse out = new MockResponse().setResponseCode(response.code()).setBody(text);
            if (type != null) out.setHeader("Content-Type", type);
            String retry = response.header("Retry-After");
            if (retry != null) out.setHeader("Retry-After", retry);
            return out;
        } catch (IOException e) {
            return new MockResponse()
                    .setResponseCode(502)
                    .setBody(
                            "{\"error\":{\"message\":\"proxy to the vendor failed: "
                                    + e.getClass().getSimpleName()
                                    + "\"}}");
        }
    }

    /** The exchanges recorded so far. */
    List<Exchange> exchanges() {
        synchronized (exchanges) {
            return List.copyOf(exchanges);
        }
    }

    int mark() {
        synchronized (exchanges) {
            return exchanges.size();
        }
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
        upstream.dispatcher().executorService().shutdown();
    }
}
