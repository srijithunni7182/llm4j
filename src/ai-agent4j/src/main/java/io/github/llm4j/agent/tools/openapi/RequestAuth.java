package io.github.llm4j.agent.tools.openapi;

import io.github.llm4j.secret.CredentialSource;
import io.github.llm4j.secret.SecretException;
import io.github.llm4j.secret.SecretMetadata;
import io.github.llm4j.secret.SecretRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The credentials an {@link OpenAPITool} puts on a request, in the three ways it can hold them: as given strings, as {@link SecretRef}s into a
 * store, and as {@link CredentialSource}s asked for on each request (the person the run is for). Everything but the given strings is fetched
 * again for every request, so nothing here goes stale.
 *
 * <p>A credential from a source is only ever sent over https, or to this machine: it is somebody's own token.
 */
final class RequestAuth {

    private final Map<String, String> headers = new HashMap<>();
    private final Map<String, String> query = new HashMap<>();
    private final Map<String, SecretRef> headerRefs = new HashMap<>();
    private final Map<String, SecretRef> queryRefs = new HashMap<>();
    private final Map<String, CredentialSource> headerSources = new HashMap<>();
    private final Map<String, CredentialSource> querySources = new HashMap<>();

    RequestAuth header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    RequestAuth query(String name, String value) {
        query.put(name, value);
        return this;
    }

    RequestAuth header(String name, SecretRef ref) {
        headerRefs.put(name, ref);
        return this;
    }

    RequestAuth query(String name, SecretRef ref) {
        queryRefs.put(name, ref);
        return this;
    }

    RequestAuth header(String name, CredentialSource source) {
        headerSources.put(name, source);
        return this;
    }

    RequestAuth query(String name, CredentialSource source) {
        querySources.put(name, source);
        return this;
    }

    /**
     * The credentials for one request to {@code baseUrl}.
     *
     * @throws SecretException when one cannot be had, or may not be sent there; the message names the credential, never its value
     */
    Resolved resolve(String baseUrl) {
        String host = SecretMetadata.hostOf(baseUrl);
        Resolved out = new Resolved(new HashMap<>(query), new HashMap<>(headers));
        out.secrets.addAll(query.values());
        out.secrets.addAll(headers.values());
        queryRefs.forEach((name, ref) -> out.putQuery(name, ref.resolveFor(host)));
        headerRefs.forEach((name, ref) -> out.putHeader(name, ref.resolveFor(host)));
        if (!headerSources.isEmpty() || !querySources.isEmpty()) {
            requireSafeTransport(baseUrl, host);
            querySources.forEach((name, source) -> out.putQuery(name, fetch(name, source, out)));
            headerSources.forEach((name, source) -> out.putHeader(name, fetch(name, source, out)));
        }
        return out;
    }

    private static String fetch(String name, CredentialSource source, Resolved into) {
        into.sources.add(source);
        String value;
        try {
            value = source.get();
        } catch (SecretException e) {
            throw e;
        } catch (RuntimeException e) {
            // the cause's message is the host's, and may hold anything: say only what failed
            throw new SecretException("credential for " + name + " could not be obtained (" + e.getClass().getSimpleName() + ")");
        }
        if (value == null || value.isBlank()) throw new SecretException("there is no credential for " + name);
        return value;
    }

    private static void requireSafeTransport(String baseUrl, String host) {
        boolean https = baseUrl != null && baseUrl.regionMatches(true, 0, "https://", 0, 8);
        boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        if (!https && !local) {
            throw new SecretException("a credential for the person running this workflow is only sent over https (this API is at "
                    + (host == null ? "an unknown host" : host) + " over http)");
        }
    }

    /** What one request carries. */
    static final class Resolved {
        private final Map<String, String> query;
        private final Map<String, String> headers;
        private final List<String> secrets = new ArrayList<>();
        private final List<CredentialSource> sources = new ArrayList<>();

        private Resolved(Map<String, String> query, Map<String, String> headers) {
            this.query = query;
            this.headers = headers;
        }

        private void putQuery(String name, String value) {
            query.put(name, value);
            secrets.add(value);
        }

        private void putHeader(String name, String value) {
            headers.put(name, value);
            secrets.add(value);
            int space = value.indexOf(' ');
            if (space > 0) secrets.add(value.substring(space + 1)); // "Bearer <token>": the token alone
        }

        Map<String, String> query() {
            return query;
        }

        Map<String, String> headers() {
            return headers;
        }

        /** Every credential value on this request, so none can come back out in a log line or an error. */
        List<String> secrets() {
            return secrets;
        }

        /** Whether asking the sources again could give a different value, so a refused request is worth repeating once. */
        boolean canRetry() {
            return !sources.isEmpty();
        }

        /** Tells every source that the server refused what it gave. */
        void rejected() {
            sources.forEach(CredentialSource::rejected);
        }
    }
}
