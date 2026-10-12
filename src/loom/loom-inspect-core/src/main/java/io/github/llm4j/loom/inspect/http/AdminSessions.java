package io.github.llm4j.loom.inspect.http;

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
import java.util.function.Supplier;

/**
 * The one administrator: a password that is read each time it is needed (so rotating it takes effect at once), a session cookie after a
 * correct one, a token that every state-changing request must carry back, and a growing delay after wrong passwords (per address, so one
 * person's typos do not lock another out). The attempted value is never logged, stored or echoed; comparison takes the same time
 * wherever the strings differ.
 */
public final class AdminSessions {

    public static final String COOKIE = "loom_inspect_session";
    public static final String CSRF_HEADER = "X-CSRF-Token";
    private static final Duration SESSION = Duration.ofHours(12);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What the caller may carry: the cookie value and the token a write must repeat. */
    public record Session(String token, String csrf) { }

    /** The result of a login attempt: a session, or how long to wait, or "no password is set". */
    public record Attempt(Session session, long retryAfterSeconds, boolean noPasswordSet) { }

    private record Live(Session session, Instant until) { }

    private record Failures(int count, Instant until) { }

    private final Supplier<String> password;
    private final Clock clock;
    private final boolean secureCookie;
    private final Map<String, Live> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    /** @param password the current admin password, or null when none is set; asked each time */
    public AdminSessions(Supplier<String> password, Clock clock, boolean secureCookie) {
        this.password = password;
        this.clock = clock;
        this.secureCookie = secureCookie;
    }

    public boolean passwordSet() {
        String p = password.get();
        return p != null && !p.isBlank();
    }

    public Attempt login(String address, String given) {
        Instant now = clock.instant();
        Failures f = failures.get(address);
        if (f != null && f.until().isAfter(now)) {
            return new Attempt(null, Math.max(1, Duration.between(now, f.until()).toSeconds()), false);
        }
        String stored = password.get();
        if (stored == null || stored.isBlank()) return new Attempt(null, 0, true);
        boolean ok = given != null && MessageDigest.isEqual(digest(given), digest(stored));
        if (!ok) {
            int n = f == null ? 1 : f.count() + 1;
            long delay = Math.min(60, 1L << Math.min(n, 6)); // 8, 16 ... capped at a minute, from the third wrong one
            failures.put(address, new Failures(n, now.plusSeconds(n >= 3 ? delay : 0)));
            return new Attempt(null, 0, false);
        }
        failures.remove(address);
        Session s = new Session(random(), random());
        sessions.put(s.token(), new Live(s, now.plus(SESSION)));
        return new Attempt(s, 0, false);
    }

    /** True when {@code given} is the password. For a sensitive step after sign-in (it counts against the same lockout, and starts no session). */
    public boolean confirm(String address, String given) {
        Attempt a = login(address, given);
        if (a.session() == null) return false;
        sessions.remove(a.session().token());
        return true;
    }

    private static String random() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static byte[] digest(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); // equal length, so the compare does not leak length
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The caller's session, or null when it has none or it ended. */
    public Session session(HttpExchange x) {
        String header = x.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE + "=")) {
                Live live = sessions.get(p.substring(COOKIE.length() + 1));
                if (live != null && live.until().isAfter(clock.instant())) return live.session();
            }
        }
        return null;
    }

    public boolean valid(HttpExchange x) {
        return session(x) != null;
    }

    public void logout(HttpExchange x) {
        Session s = session(x);
        if (s != null) sessions.remove(s.token());
    }

    public String setCookie(Session s) {
        return COOKIE + "=" + s.token() + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=" + SESSION.toSeconds() + (secureCookie ? "; Secure" : "");
    }

    public String clearCookie() {
        return COOKIE + "=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0" + (secureCookie ? "; Secure" : "");
    }
}
