package io.github.llm4j.loom.observe;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where a process reports to a Fabric server and how it decides to trust it. The key is not here: it is read from the process's own
 * secret store, so no key appears on a command line or in the environment.
 *
 * @param url the server, {@code https} (plain {@code http} only for a loopback address)
 * @param ca a PEM file with the public certificate of a private CA, added to the default roots for this connection only; or null
 * @param caOnly trust only {@code ca}, not the default roots
 * @param pin the SHA-256 of the server's public key, lower-case hex; or null
 * @param outbox where the durable local log lives; or null for the default
 * @param keySecret the name of the secret holding this process's client key
 * @param flushTimeout how long to try to deliver what is left when the process ends
 */
public record FabricOptions(URI url, Path ca, boolean caOnly, String pin, Path outbox, String keySecret, Duration flushTimeout) {

    public static final String DEFAULT_KEY_SECRET = "LOOM_FABRIC_CLIENT_KEY";
    public static final Duration DEFAULT_FLUSH = Duration.ofSeconds(5);
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

    public FabricOptions {
        keySecret = keySecret == null || keySecret.isBlank() ? DEFAULT_KEY_SECRET : keySecret.trim();
        flushTimeout = flushTimeout == null ? DEFAULT_FLUSH : flushTimeout;
    }

    /** Checks and builds the options from the command-line words; any of them may be null. Throws {@link IllegalArgumentException} with a message to show. */
    public static FabricOptions parse(String url, String name, String ca, boolean caOnly, String pin, String outbox, String keySecret, String flush) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("--observe-fabric needs --observe-name: the name this process has on the server (the one given to 'fabric clients add'). There is no default.");
        }
        if (!NAME.matcher(name.trim()).matches()) {
            throw new IllegalArgumentException("--observe-name may use letters, digits, '.', '_' and '-' (up to 64), got '" + name + "'");
        }
        URI u;
        try {
            u = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--observe-fabric takes a URL like https://fabric.example.com:9090, got '" + url + "'");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (u.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new IllegalArgumentException("--observe-fabric takes an https URL like https://fabric.example.com:9090, got '" + url + "'");
        }
        if (u.getUserInfo() != null) throw new IllegalArgumentException("--observe-fabric must not carry a user name or password in the URL");
        if (scheme.equals("http") && !loopback(u.getHost())) {
            throw new IllegalArgumentException("--observe-fabric needs https: plain http is only allowed for a loopback address, got '" + url + "'");
        }
        Path caPath = null;
        if (ca != null && !ca.isBlank()) {
            caPath = Path.of(ca.trim());
            if (!Files.isRegularFile(caPath)) throw new IllegalArgumentException("--observe-ca: no such file: " + ca);
        }
        if (caOnly && caPath == null) throw new IllegalArgumentException("--observe-ca-only needs --observe-ca");
        String pinned = null;
        if (pin != null && !pin.isBlank()) {
            pinned = pin.trim().toLowerCase(Locale.ROOT);
            if (pinned.startsWith("sha256:")) pinned = pinned.substring(7);
            pinned = pinned.replace(":", "");
            if (!HEX.matcher(pinned).matches()) throw new IllegalArgumentException("--observe-pin takes sha256:<64 hex digits>, as printed by the server at start-up");
        }
        if (caPath != null && pinned != null) throw new IllegalArgumentException("use --observe-ca or --observe-pin, not both");
        return new FabricOptions(u, caPath, caOnly, pinned, outbox == null || outbox.isBlank() ? null : Path.of(outbox.trim()), keySecret, flush == null || flush.isBlank() ? null : span(flush));
    }

    private static boolean loopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1");
    }

    private static Duration span(String text) {
        var m = Pattern.compile("\\s*(\\d+)\\s*(s|m)\\s*").matcher(text);
        if (!m.matches()) throw new IllegalArgumentException("--observe-flush-timeout takes a time like 5s or 1m, got '" + text + "'");
        long n = Long.parseLong(m.group(1));
        return m.group(2).equals("s") ? Duration.ofSeconds(n) : Duration.ofMinutes(n);
    }
}
