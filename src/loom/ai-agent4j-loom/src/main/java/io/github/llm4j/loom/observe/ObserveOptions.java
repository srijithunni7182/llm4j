package io.github.llm4j.loom.observe;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What {@code weave --observe} asks for, parsed and checked. Not saved in a run's spec: like secrets, it is given again each time.
 *
 * @param port the port of the page; 0 picks a free one
 * @param bind the address to listen on
 * @param detail how much text may leave the run: full, truncated or metadata
 * @param name this process's name as shown on the page
 * @param linger how long to keep the page up after the run ends, so its final state can be read
 * @param embedded serve the page from this process ({@code --observe}); false when only reporting to a Fabric server
 * @param fabric where to report, or null
 * @param otlp an OpenTelemetry endpoint to export to, or null
 */
public record ObserveOptions(int port, String bind, String detail, String name, Duration linger, boolean embedded, FabricOptions fabric, OtlpOptions otlp) {

    public static final int DEFAULT_PORT = 8787;
    public static final String DEFAULT_BIND = "127.0.0.1";
    private static final Pattern SPAN = Pattern.compile("\\s*(\\d+)\\s*([smh])\\s*");

    /** The embedded page only. */
    public ObserveOptions(int port, String bind, String detail, String name, Duration linger) {
        this(port, bind, detail, name, linger, true, null, null);
    }

    public ObserveOptions(int port, String bind, String detail, String name, Duration linger, boolean embedded, FabricOptions fabric) {
        this(port, bind, detail, name, linger, embedded, fabric, null);
    }

    public ObserveOptions {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("--observe takes a port from 0 to 65535, got " + port);
        bind = bind == null || bind.isBlank() ? DEFAULT_BIND : bind.trim();
        detail = detail == null || detail.isBlank() ? "truncated" : detail.trim().toLowerCase(Locale.ROOT);
        if (!detail.equals("full") && !detail.equals("truncated") && !detail.equals("metadata")) {
            throw new IllegalArgumentException("--observe-detail takes full, truncated or metadata, got '" + detail + "'");
        }
        name = name == null || name.isBlank() ? "local" : name.trim();
        linger = linger == null ? Duration.ZERO : linger;
    }

    /** From the command-line words; any of them may be null. */
    public static ObserveOptions parse(String port, String bind, String detail, String name, String linger) {
        int p;
        if (port == null || port.isBlank()) {
            p = DEFAULT_PORT;
        } else {
            try {
                p = Integer.parseInt(port.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--observe takes a port number, got '" + port + "'");
            }
        }
        return new ObserveOptions(p, bind, detail, name, parseLinger(linger));
    }

    /**
     * From everything the command line (and the environment) can say. {@code observe} is the {@code --observe} value or null when the
     * flag is absent; {@code fabricUrl} is {@code --observe-fabric} or null. Returns null when neither asks for anything.
     */
    public static ObserveOptions fromCommandLine(String observe, String bind, String detail, String name, String linger, String fabricUrl, String ca,
                                                 boolean caOnly, String pin, String outbox, String keySecret, String flush, java.util.function.Function<String, String> env) {
        return fromCommandLine(observe, bind, detail, name, linger, fabricUrl, ca, caOnly, pin, outbox, keySecret, flush, null, null, env);
    }

    /** As above with an OTLP endpoint and its headers ({@code --observe-otlp}, {@code --observe-otlp-header}). */
    public static ObserveOptions fromCommandLine(String observe, String bind, String detail, String name, String linger, String fabricUrl, String ca,
                                                 boolean caOnly, String pin, String outbox, String keySecret, String flush, String otlp, java.util.List<String> otlpHeaders,
                                                 java.util.function.Function<String, String> env) {
        String url = first(fabricUrl, env.apply("LOOM_FABRIC_URL"));
        String collector = first(otlp, null);
        if (observe == null && url == null && collector == null) return null;
        OtlpOptions exporting = collector == null ? null : OtlpOptions.parse(collector, otlpHeaders, name, outbox);
        FabricOptions fabric = url == null ? null
                : FabricOptions.parse(url, name, first(ca, env.apply("LOOM_FABRIC_CA")), caOnly, first(pin, env.apply("LOOM_FABRIC_PIN")), outbox, keySecret, flush);
        ObserveOptions base = parse(observe, bind, detail, name, linger);
        return new ObserveOptions(base.port(), base.bind(), base.detail(), base.name(), base.linger(), observe != null, fabric, exporting);
    }

    private static String first(String a, String b) {
        return a != null && !a.isBlank() ? a : b != null && !b.isBlank() ? b : null;
    }

    private static Duration parseLinger(String text) {
        if (text == null || text.isBlank()) return Duration.ZERO;
        Matcher m = SPAN.matcher(text);
        if (!m.matches()) throw new IllegalArgumentException("--observe-linger takes a time like 30s, 5m or 1h, got '" + text + "'");
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
    }
}
