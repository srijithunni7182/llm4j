package io.github.llm4j.loom.fabric;

import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one administrator: a password in the vault, a session cookie after a correct one, and a growing delay after wrong ones (per
 * address, so one person's typos do not lock another out). The attempted value is never logged, stored or echoed; comparison takes the
 * same time wherever the strings differ.
 */
final class Admin {

    static final String COOKIE = "loom_fabric_session";
    private static final Duration SESSION = Duration.ofHours(12);
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Failures(int count, Instant until) { }

    private final Vault vault;
    private final Clock clock;
    private final boolean secureCookie;
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    Admin(Vault vault, Clock clock, boolean secureCookie) {
        this.vault = vault;
        this.clock = clock;
        this.secureCookie = secureCookie;
    }

    /** The result of a login attempt: a session token, or how long to wait. */
    record Attempt(String token, long retryAfterSeconds, boolean noPasswordSet) { }

    Attempt login(String address, String password) {
        Instant now = clock.instant();
        Failures f = failures.get(address);
        if (f != null && f.until().isAfter(now)) {
            return new Attempt(null, Math.max(1, Duration.between(now, f.until()).toSeconds()), false);
        }
        String stored = vault.get(Vault.ADMIN);
        if (stored == null) return new Attempt(null, 0, true);
        boolean ok = password != null && MessageDigest.isEqual(digest(password), digest(stored));
        if (!ok) {
            int n = f == null ? 1 : f.count() + 1;
            long delay = Math.min(60, 1L << Math.min(n, 6)); // 2, 4, 8 ... capped at a minute
            failures.put(address, new Failures(n, now.plusSeconds(n >= 3 ? delay : 0)));
            return new Attempt(null, 0, false);
        }
        failures.remove(address);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.put(token, now.plus(SESSION));
        return new Attempt(token, 0, false);
    }

    private static byte[] digest(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); // equal length, so the compare does not leak length
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    boolean valid(HttpExchange x) {
        String header = x.getRequestHeaders().getFirst("Cookie");
        if (header == null) return false;
        for (String part : header.split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE + "=")) {
                Instant until = sessions.get(p.substring(COOKIE.length() + 1));
                if (until != null && until.isAfter(clock.instant())) return true;
            }
        }
        return false;
    }

    void logout(HttpExchange x) {
        String header = x.getRequestHeaders().getFirst("Cookie");
        if (header == null) return;
        for (String part : header.split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE + "=")) sessions.remove(p.substring(COOKIE.length() + 1));
        }
    }

    String setCookie(String token) {
        return COOKIE + "=" + token + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=" + SESSION.toSeconds() + (secureCookie ? "; Secure" : "");
    }

    String clearCookie() {
        return COOKIE + "=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0" + (secureCookie ? "; Secure" : "");
    }
}
