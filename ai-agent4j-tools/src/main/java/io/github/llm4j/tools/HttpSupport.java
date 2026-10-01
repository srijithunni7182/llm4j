package io.github.llm4j.tools;
import io.github.llm4j.agent.tool.EffectContext;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLHandshakeException;
import okhttp3.Dns;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Sends HTTP requests for tools, under a {@link NetPolicy}: bounded time and size, explicit redirects, and
 * retries that know the difference between "never sent" and "may have been delivered".
 */
public final class HttpSupport {

    /** How much to retry. */
    public enum Retry {
        /** Never. */
        NONE,
        /** Failures that provably sent nothing, and 429/5xx replies: right for a webhook. */
        UNSENT_AND_STATUS,
        /** Also a request that may have been delivered: only for requests that are safe to repeat. */
        ALL
    }

    /** A completed exchange. */
    public record Reply(int status, String reason, String contentType, byte[] body, boolean truncated, Headers headers) {
        public String bodyText() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }

        public boolean success() {
            return status >= 200 && status < 300;
        }

        public Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name));
        }
    }

    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);
    private static final int MAX_REDIRECTS = 3;

    private final OkHttpClient client;
    private final NetPolicy policy;
    private final EffectContext context;
    private final long maxBytes;
    private final int retries;

    public HttpSupport(NetPolicy policy, EffectContext context, Duration timeout, long maxBytes, int retries) {
        this.policy = policy;
        this.context = context;
        this.maxBytes = maxBytes;
        this.retries = retries;
        this.client = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .callTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * Sends the request, retrying as {@code mode} allows.
     *
     * @throws ToolRefusal when the policy refuses it, or it can't be sent at all
     * @throws UnknownOutcomeException when it may have been delivered and mode doesn't allow repeating it
     */
    public Reply send(Request request, Retry mode) {
        int attempt = 0;
        while (true) {
            Reply reply;
            try {
                reply = exchange(request);
            } catch (UnsentFailure e) {
                if (mode == Retry.NONE || attempt >= retries) throw new ToolRefusal("couldn't reach " + request.url().host() + " (" + e.kind + ")");
                backoff(attempt++, null);
                continue;
            } catch (MaybeSentFailure e) {
                if (mode != Retry.ALL) {
                    throw new UnknownOutcomeException("no complete answer from " + request.url().host() + " (" + e.kind
                            + "); the request may have been delivered");
                }
                if (attempt >= retries) throw new ToolRefusal("no answer from " + request.url().host() + " (" + e.kind + ")");
                backoff(attempt++, null);
                continue;
            }
            boolean retriableStatus = reply.status() == 429 || reply.status() >= 500;
            if (retriableStatus && mode != Retry.NONE && attempt < retries) {
                Duration wait = retryAfter(reply).orElse(null);
                if (wait == null || wait.compareTo(MAX_RETRY_AFTER) <= 0) {
                    backoff(attempt++, wait);
                    continue;
                }
            }
            return reply;
        }
    }

    /** How long the reply asks us to wait, if it says. */
    public Optional<Duration> retryAfter(Reply reply) {
        String value = reply.headers().get("Retry-After");
        if (value == null) return Optional.empty();
        String v = value.trim();
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(v)));
        } catch (NumberFormatException notSeconds) {
            try {
                Instant at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration d = Duration.between(context.clock().instant(), at);
                return Optional.of(d.isNegative() ? Duration.ZERO : d);
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        }
    }

    // ── One exchange, following redirects ────────────────────────────────────────────────────

    private Reply exchange(Request original) {
        Request request = original;
        for (int hop = 0; ; hop++) {
            Reply reply = singleHop(request);
            if (!policy.rules().followRedirects() || reply.status() < 300 || reply.status() >= 400
                    || reply.header("Location").isEmpty()) {
                return reply;
            }
            if (hop >= MAX_REDIRECTS) throw new ToolRefusal("refused: more than " + MAX_REDIRECTS + " redirects");
            HttpUrl target = request.url().resolve(reply.header("Location").get());
            if (target == null) throw new ToolRefusal("refused: a redirect to an address that can't be read");
            String refusal = redirectProblem(request.url(), target);
            if (refusal != null) throw new ToolRefusal("refused: " + refusal);
            Request.Builder next = request.newBuilder().url(target);
            if (!sameOrigin(request.url(), target)) {
                // Like a browser or OkHttp itself: a credential meant for one origin is not sent to another.
                for (String name : request.headers().names()) if (Options.isSecretHeader(name)) next.removeHeader(name);
            }
            request = next.build();
        }
    }

    private static boolean sameOrigin(HttpUrl a, HttpUrl b) {
        return a.scheme().equals(b.scheme()) && a.host().equalsIgnoreCase(b.host()) && a.port() == b.port();
    }

    /** Why a redirect from one URL to another must not be followed, or null. */
    public static String redirectProblem(HttpUrl from, HttpUrl to) {
        if (from.isHttps() && !to.isHttps()) return "a redirect from https to http";
        return null;
    }

    private Reply singleHop(Request request) {
        String problem = policy.checkUrl(request.url());
        if (problem != null) throw new ToolRefusal("refused: " + problem);
        List<InetAddress> checked;
        try {
            checked = policy.resolveChecked(request.url().host());
        } catch (UnknownHostException e) {
            throw new UnsentFailure("unknown host");
        }
        // Connect to exactly the addresses that were checked; anything else (a proxy) resolves normally.
        String host = request.url().host();
        Dns pinned = name -> name.equalsIgnoreCase(host) ? checked : Dns.SYSTEM.lookup(name);
        try (Response response = client.newBuilder().dns(pinned).build().newCall(request).execute()) {
            ResponseBody body = response.body();
            Limits.Capped capped = body == null ? new Limits.Capped(new byte[0], false) : readBody(body);
            String type = response.header("Content-Type");
            return new Reply(response.code(), response.message(), type, capped.bytes(), capped.truncated(), response.headers());
        } catch (IOException e) {
            throw classify(e);
        }
    }

    private Limits.Capped readBody(ResponseBody body) throws IOException {
        return Limits.readCapped(body.byteStream(), maxBytes);
    }

    private static RuntimeException classify(IOException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof ConnectException || t instanceof UnknownHostException || t instanceof SSLHandshakeException) {
                return new UnsentFailure(t instanceof UnknownHostException ? "unknown host" : t instanceof SSLHandshakeException ? "TLS failed" : "connection refused");
            }
            if (t instanceof SocketTimeoutException s && s.getMessage() != null && s.getMessage().toLowerCase(Locale.ROOT).contains("connect")) {
                return new UnsentFailure("connect timed out");
            }
            t = t.getCause();
        }
        return new MaybeSentFailure(e instanceof SocketTimeoutException ? "timed out" : "connection lost");
    }

    private void backoff(int attempt, Duration retryAfter) {
        Duration wait = retryAfter != null ? retryAfter : Duration.ofSeconds(1L << Math.min(attempt, 4));
        try {
            context.sleeper().sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolRefusal("interrupted while waiting to retry");
        }
    }

    private static final class UnsentFailure extends RuntimeException {
        final String kind;

        UnsentFailure(String kind) {
            super(kind, null, false, false);
            this.kind = kind;
        }
    }

    private static final class MaybeSentFailure extends RuntimeException {
        final String kind;

        MaybeSentFailure(String kind) {
            super(kind, null, false, false);
            this.kind = kind;
        }
    }
}
