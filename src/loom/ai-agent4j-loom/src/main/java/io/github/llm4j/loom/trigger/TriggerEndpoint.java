package io.github.llm4j.loom.trigger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * An HTTP tick for hosts that scale to zero: a cloud scheduler calls {@code POST /loom/tick} every few
 * minutes and this fires what is due. Framework-neutral — pass the method and headers from your servlet,
 * Spring controller or function handler and send back the response.
 *
 * <p>Callers authenticate with {@code X-Loom-Token} (compared with a shared secret from the environment,
 * {@code LOOM_TRIGGER_TOKEN}) or {@code Authorization: Bearer <token>} checked by a {@link BearerVerifier}
 * (e.g. Google OIDC verification for Cloud Scheduler). Without either configured, every call is refused.
 */
public final class TriggerEndpoint {

    public static final String TOKEN_ENV = "LOOM_TRIGGER_TOKEN";

    public record Response(int status, String body) { }

    /** Verifies a bearer token (for example an OIDC ID token's signature and audience). */
    @FunctionalInterface
    public interface BearerVerifier {
        boolean verify(String token);
    }

    private final TriggerRunner runner;
    private final Supplier<String> sharedToken;
    private final BearerVerifier bearer;

    public TriggerEndpoint(TriggerRunner runner, Supplier<String> sharedToken, BearerVerifier bearer) {
        this.runner = runner;
        this.sharedToken = sharedToken != null ? sharedToken : () -> null;
        this.bearer = bearer;
    }

    /** Uses {@value #TOKEN_ENV} from the environment as the shared token. */
    public static TriggerEndpoint fromEnvironment(TriggerRunner runner, BearerVerifier bearer) {
        return new TriggerEndpoint(runner, () -> System.getenv(TOKEN_ENV), bearer);
    }

    public Response handle(String method, Map<String, String> headers) {
        if (!"POST".equalsIgnoreCase(method)) return new Response(405, "{\"error\":\"use POST\"}");
        Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) h.putAll(headers);
        if (!authorized(h)) return new Response(401, "{\"error\":\"unauthorized\"}");
        int fired = runner.tick();
        return new Response(200, "{\"fired\":" + fired + "}");
    }

    private boolean authorized(Map<String, String> h) {
        String expected = sharedToken.get();
        String given = h.get("X-Loom-Token");
        if (expected != null && !expected.isBlank() && given != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        String auth = h.get("Authorization");
        if (bearer != null && auth != null && auth.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            try {
                return bearer.verify(auth.substring(7).trim());
            } catch (RuntimeException e) {
                return false;
            }
        }
        return false;
    }
}
