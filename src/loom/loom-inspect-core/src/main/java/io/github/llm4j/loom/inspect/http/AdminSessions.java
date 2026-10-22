package io.github.llm4j.loom.inspect.http;

import com.sun.net.httpserver.HttpExchange;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The one administrator: a password that is read each time it is needed (so rotating it takes
 * effect at once), a session cookie after a correct one, a token that every state-changing request
 * must carry back, and a growing delay after wrong passwords (per address, so one person's typos do
 * not lock another out). The attempted value is never logged, stored or echoed; comparison takes
 * the same time wherever the strings differ.
 */
public final class AdminSessions {

    public static final String COOKIE = "loom_inspect_session";
    public static final String CSRF_HEADER = "X-CSRF-Token";
    private static final Duration SESSION = Duration.ofHours(12);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What the caller may carry: the cookie value and the token a write must repeat. */
    public record Session(String token, String csrf) {}

    /** The result of a login attempt: a session, or how long to wait, or "no password is set". */
    public record Attempt(Session session, long retryAfterSeconds, boolean noPasswordSet) {}

    private record Live(Session session, Instant until) {}

    private record Failures(int count, Instant until, Instant last) {}

    /** A wrong guess is forgotten after this long without another. */
    static final Duration FORGET = Duration.ofMinutes(15);

    /**
     * Wrong guesses from anywhere within a minute before every sign-in has to wait: a guess spread
     * over many addresses is still a guess.
     */
    static final int GLOBAL_PER_MINUTE = 50;

    private static final int MAX_TRACKED = 10_000;
    private static final int MAX_SESSIONS = 100;

    private final Supplier<String> password;
    private final Clock clock;
    private final boolean secureCookie;
    private final Map<String, Live> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final java.util.ArrayDeque<Instant> recent = new java.util.ArrayDeque<>();

    /**
     * @param password the current admin password, or null when none is set; asked each time
     */
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
        long wait = globalWait(now);
        if (wait > 0) return new Attempt(null, wait, false);
        Failures f = failures.get(address);
        if (f != null && f.last().plus(FORGET).isBefore(now)) f = null; // long ago: start again
        if (f != null && f.until().isAfter(now)) {
            return new Attempt(
                    null, Math.max(1, Duration.between(now, f.until()).toSeconds()), false);
        }
        String stored = password.get();
        if (stored == null || stored.isBlank()) return new Attempt(null, 0, true);
        boolean ok = PasswordHash.matches(stored, given);
        if (!ok) {
            int n = f == null ? 1 : f.count() + 1;
            long delay =
                    Math.min(
                            60,
                            1L << Math.min(n, 6)); // 8, 16 ... capped at a minute, from the third
            // wrong one
            if (failures.size() >= MAX_TRACKED)
                failures.values().removeIf(x -> x.last().plus(FORGET).isBefore(now));
            if (failures.size() < MAX_TRACKED)
                failures.put(address, new Failures(n, now.plusSeconds(n >= 3 ? delay : 0), now));
            synchronized (recent) {
                recent.addLast(now);
            }
            return new Attempt(null, 0, false);
        }
        failures.remove(address);
        sessions.values().removeIf(l -> !l.until().isAfter(now));
        if (sessions.size() >= MAX_SESSIONS) { // an old session makes room: the oldest ends first
            sessions.entrySet().stream()
                    .min(java.util.Comparator.comparing(e -> e.getValue().until()))
                    .ifPresent(e -> sessions.remove(e.getKey()));
        }
        Session s = new Session(random(), random());
        sessions.put(s.token(), new Live(s, now.plus(SESSION)));
        return new Attempt(s, 0, false);
    }

    /** Seconds every sign-in must wait because of too many wrong guesses from everywhere, or 0. */
    private long globalWait(Instant now) {
        synchronized (recent) {
            while (!recent.isEmpty() && recent.peekFirst().plusSeconds(60).isBefore(now))
                recent.removeFirst();
            if (recent.size() < GLOBAL_PER_MINUTE) return 0;
            return Math.max(
                    1, Duration.between(now, recent.peekFirst().plusSeconds(60)).toSeconds());
        }
    }

    /**
     * True when {@code given} is the password. For a sensitive step after sign-in (it counts
     * against the same lockout, and starts no session).
     */
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
        return COOKIE
                + "="
                + s.token()
                + "; HttpOnly; SameSite=Strict; Path=/; Max-Age="
                + SESSION.toSeconds()
                + (secureCookie ? "; Secure" : "");
    }

    public String clearCookie() {
        return COOKIE
                + "=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0"
                + (secureCookie ? "; Secure" : "");
    }
}
