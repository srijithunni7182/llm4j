package io.github.llm4j.loom.tools.generic;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Typed reads over a tool's resolved options. Every failure is an {@link OptionException} whose message
 * names the option, so a kind's {@code check} can return it as a load error and {@code create} can trust
 * what it reads.
 */
public final class Options {

    /** Option-name prefix for fixed request headers: {@code header.Accept}. */
    public static final String HEADER_PREFIX = "header.";

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h)?");
    private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*(b|k|kb|m|mb)?", Pattern.CASE_INSENSITIVE);
    private static final List<String> SECRET_HEADER_WORDS =
            List.of("authorization", "token", "key", "secret", "cookie", "password");

    private final Map<String, String> values;

    public Options(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    /** The value, or null. */
    public String get(String name) {
        return values.get(name);
    }

    public String require(String name) {
        String v = values.get(name);
        if (v == null || v.isBlank()) throw new OptionException(name + ": is required");
        return v;
    }

    public String string(String name, String fallback) {
        String v = values.get(name);
        return v == null ? fallback : v;
    }

    public boolean bool(String name, boolean fallback) {
        String v = values.get(name);
        if (v == null) return fallback;
        if (v.equals("true")) return true;
        if (v.equals("false")) return false;
        throw new OptionException(name + ": must be true or false, not " + v);
    }

    public int integer(String name, int fallback, int min, int max) {
        String v = values.get(name);
        if (v == null) return fallback;
        int n;
        try {
            n = Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new OptionException(name + ": must be a whole number, not " + v);
        }
        if (n < min || n > max) throw new OptionException(name + ": must be between " + min + " and " + max + ", not " + n);
        return n;
    }

    public Duration duration(String name, Duration fallback, Duration max) {
        String v = values.get(name);
        if (v == null) return fallback;
        Duration d = parseDuration(v);
        if (d == null) throw new OptionException(name + ": must be a duration like 500ms, 20s or 2m, not " + v);
        if (d.isZero() || d.compareTo(max) > 0) {
            throw new OptionException(name + ": must be above zero and at most " + describe(max) + ", not " + v);
        }
        return d;
    }

    public long size(String name, long fallback, long max) {
        String v = values.get(name);
        if (v == null) return fallback;
        long n = parseSize(v);
        if (n < 0) throw new OptionException(name + ": must be a size like 64k or 1m, not " + v);
        if (n == 0 || n > max) throw new OptionException(name + ": must be above zero and at most " + max + " bytes, not " + v);
        return n;
    }

    public String choice(String name, String fallback, String... allowed) {
        String v = values.get(name);
        if (v == null) return fallback;
        String lower = v.toLowerCase(Locale.ROOT);
        for (String a : allowed) if (a.equals(lower)) return a;
        throw new OptionException(name + ": must be one of " + String.join(", ", allowed) + ", not " + v);
    }

    /** A comma-separated list, trimmed, with empty entries dropped. */
    public List<String> list(String name) {
        String v = values.get(name);
        if (v == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : v.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return List.copyOf(out);
    }

    /** The {@code header.*} options, by header name. */
    public Map<String, String> headers() {
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            if (k.startsWith(HEADER_PREFIX) && k.length() > HEADER_PREFIX.length()) out.put(k.substring(HEADER_PREFIX.length()), v);
        });
        return out;
    }

    /** True when a header's value is a credential, so the script must take it from the environment. */
    public static boolean isSecretHeader(String headerName) {
        String n = headerName.toLowerCase(Locale.ROOT);
        return SECRET_HEADER_WORDS.stream().anyMatch(n::contains);
    }

    /** {@code 500ms}, {@code 20s}, {@code 2m}, {@code 1h}; a bare number is seconds. Null if malformed. */
    public static Duration parseDuration(String text) {
        Matcher m = DURATION.matcher(text.trim());
        if (!m.matches()) return null;
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "s" : m.group(2);
        return switch (unit) {
            case "ms" -> Duration.ofMillis(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofSeconds(n);
        };
    }

    /** {@code 64k}, {@code 1m}, {@code 512}: bytes. Negative if malformed. */
    public static long parseSize(String text) {
        Matcher m = SIZE.matcher(text.trim());
        if (!m.matches()) return -1;
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "b" : m.group(2).toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "k", "kb" -> n * 1024;
            case "m", "mb" -> n * 1024 * 1024;
            default -> n;
        };
    }

    private static String describe(Duration d) {
        return d.toMinutes() > 0 && d.toSeconds() % 60 == 0 ? d.toMinutes() + "m" : d.toSeconds() + "s";
    }

    @Override
    public String toString() {
        return Arrays.toString(values.keySet().toArray());
    }
}
