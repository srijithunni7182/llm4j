package io.github.llm4j.loom.inspect.wire;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signature on every request to a server: {@code X-Loom-Inspect-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(key, t + "." + body)>}.
 * The timestamp bounds replay; each process has its own key, so one can be revoked alone.
 */
public final class Signer {

    public static final String HEADER = "X-Loom-Inspect-Signature";
    public static final String CLIENT_HEADER = "X-Loom-Inspect-Client";
    public static final Duration WINDOW = Duration.ofMinutes(5);

    private Signer() { }

    public static String sign(String key, Instant at, byte[] body) {
        long t = at.getEpochSecond();
        return "t=" + t + ",v1=" + HexFormat.of().formatHex(mac(key, t, body));
    }

    /** True when the header is well formed, is inside the replay window around {@code now}, and matches the body under {@code key}. */
    public static boolean verify(String key, String header, byte[] body, Instant now) {
        if (key == null || header == null) return false;
        long t = -1;
        String sig = null;
        for (String part : header.split(",")) {
            String p = part.trim();
            if (p.startsWith("t=")) {
                try {
                    t = Long.parseLong(p.substring(2));
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (p.startsWith("v1=")) {
                sig = p.substring(3);
            }
        }
        if (t < 0 || sig == null) return false;
        if (Math.abs(now.getEpochSecond() - t) > WINDOW.toSeconds()) return false;
        byte[] given;
        try {
            given = HexFormat.of().parseHex(sig);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(given, mac(key, t, body)); // constant time
    }

    private static byte[] mac(String key, long t, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((t + ".").getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
