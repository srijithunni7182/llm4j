package io.github.llm4j.ratelimit;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Reads rate-limit information from one kind of provider response. Implementations are pure functions
 * of the response and the clock, and never throw: anything they can't read is simply not theirs.
 */
@FunctionalInterface
public interface RateLimitParser {

    Optional<RateLimitInfo> parse(Response response, Clock clock);

    /** The parts of an HTTP response a parser may look at. Header names are case-insensitive. */
    final class Response {
        private final String provider;
        private final int status;
        private final Map<String, List<String>> headers;
        private final String body;

        public Response(String provider, int status, Map<String, List<String>> headers, String body) {
            this.provider = provider == null ? "unknown" : provider;
            this.status = status;
            TreeMap<String, List<String>> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (headers != null) h.putAll(headers);
            this.headers = h;
            this.body = body == null ? "" : body;
        }

        public String provider() {
            return provider;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }

        /** The first value of a header, trimmed, or null. */
        public String header(String name) {
            List<String> values = headers.get(name);
            if (values == null || values.isEmpty() || values.get(0) == null) return null;
            String v = values.get(0).trim();
            return v.isEmpty() ? null : v;
        }

        public boolean hasHeaderPrefix(String prefix) {
            String p = prefix.toLowerCase(Locale.ROOT);
            return headers.keySet().stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).startsWith(p));
        }
    }
}
