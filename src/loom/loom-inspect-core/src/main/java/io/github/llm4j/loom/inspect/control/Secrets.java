package io.github.llm4j.loom.inspect.control;

import java.util.List;
import java.util.Map;

/**
 * The page's secrets: names and what is known about them, never values; and one write, replacing
 * the value of a secret that already exists. Only an embedded page has one: a secret belongs to the
 * application's own store, and values never travel to a server.
 */
public interface Secrets {

    /**
     * One row per secret: name, source, allowedHosts, updatedAt, fingerprint, usedBy, writable. No
     * values.
     */
    List<Map<String, Object>> list();

    /**
     * Replaces the value of the existing secret {@code name}. The array is wiped by the caller. The
     * message never contains the value.
     */
    Controls.Outcome rotate(String name, char[] value, String who);

    /**
     * First four bytes of SHA-256 of the value as {@code 3b1e…90}: enough to see that a value
     * changed or matches, not to learn it.
     */
    static String fingerprint(String value) {
        try {
            byte[] d =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String hex = java.util.HexFormat.of().formatHex(d, 0, 4);
            return hex.substring(0, 4) + "…" + hex.substring(6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
