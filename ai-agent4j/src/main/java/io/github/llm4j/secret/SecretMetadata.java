package io.github.llm4j.secret;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What is known about a secret besides its value. It never contains the value.
 *
 * @param allowedHosts the hosts the secret may be sent to: exact hosts or {@code *.suffix} patterns (which match subdomains, not the apex), no
 *     scheme or port, compared case-insensitively. Empty means unrestricted.
 * @param description free text for people, or null
 * @param updatedAt when the value was last set, or null when unknown
 */
public record SecretMetadata(Set<String> allowedHosts, String description, Instant updatedAt) {

    /** No restrictions, no description. */
    public static final SecretMetadata NONE = new SecretMetadata(Set.of(), null, null);

    private static final Pattern HOST = Pattern.compile("(\\*\\.)?[a-z0-9]([a-z0-9._-]*[a-z0-9])?|\\[?[0-9a-f:]*:[0-9a-f:]*]?");

    public SecretMetadata {
        Set<String> normalized = new TreeSet<>();
        if (allowedHosts != null) {
            for (String h : allowedHosts) normalized.add(requireHostPattern(h));
        }
        allowedHosts = Collections.unmodifiableSet(new LinkedHashSet<>(normalized));
    }

    /** Restricted to these hosts. */
    public static SecretMetadata allowing(String... hosts) {
        return new SecretMetadata(Set.of(hosts), null, null);
    }

    public SecretMetadata withDescription(String text) {
        return new SecretMetadata(allowedHosts, text, updatedAt);
    }

    public SecretMetadata withUpdatedAt(Instant when) {
        return new SecretMetadata(allowedHosts, description, when);
    }

    /** True when the secret may be sent to {@code host} (always true when unrestricted). A null or blank host is never allowed by a restricted secret. */
    public boolean allows(String host) {
        if (allowedHosts.isEmpty()) return true;
        String h = normalizeHost(host);
        if (h == null) return false;
        for (String pattern : allowedHosts) {
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1); // ".example.com"
                if (h.length() > suffix.length() && h.endsWith(suffix)) return true;
            } else if (pattern.equals(h)) {
                return true;
            }
        }
        return false;
    }

    /** The host of a URL, or of a bare host name; null when there is none. */
    public static String hostOf(String urlOrHost) {
        if (urlOrHost == null || urlOrHost.isBlank()) return null;
        String s = urlOrHost.trim();
        try {
            if (s.contains("://")) {
                String host = URI.create(s).getHost();
                return normalizeHost(host);
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return normalizeHost(s);
    }

    private static String normalizeHost(String host) {
        if (host == null) return null;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.contains("]")) h = h.substring(1, h.indexOf(']'));
        else if (h.indexOf(':') == h.lastIndexOf(':') && h.contains(":")) h = h.substring(0, h.indexOf(':')); // host:port
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h.isEmpty() ? null : h;
    }

    private static String requireHostPattern(String pattern) {
        if (pattern == null) throw new IllegalArgumentException("an allowed host must not be null");
        String h = pattern.trim().toLowerCase(Locale.ROOT);
        if (h.contains("/") || h.contains("@") || h.contains(" ") || !HOST.matcher(h).matches()) {
            throw new IllegalArgumentException("allowed host \"" + pattern + "\" must be a host name or *.suffix, without scheme, port or path");
        }
        if (h.startsWith("[")) h = h.substring(1, h.length() - 1);
        return h;
    }
}
