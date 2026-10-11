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
 */
public record ObserveOptions(int port, String bind, String detail, String name, Duration linger) {

    public static final int DEFAULT_PORT = 8787;
    public static final String DEFAULT_BIND = "127.0.0.1";
    private static final Pattern SPAN = Pattern.compile("\\s*(\\d+)\\s*([smh])\\s*");

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
