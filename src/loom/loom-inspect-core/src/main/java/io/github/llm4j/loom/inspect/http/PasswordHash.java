package io.github.llm4j.loom.inspect.http;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The admin password as it may be stored: either the password itself, or a salted PBKDF2-HMAC-SHA256 hash of it written
 * {@code pbkdf2-sha256$<iterations>$<salt>$<hash>}. A stored hash means the secret store (or a backup, or a screen share) never holds a password someone could
 * type in. Both forms are accepted so an existing setup keeps working.
 */
public final class PasswordHash {

    static final String PREFIX = "pbkdf2-sha256$";
    static final int ITERATIONS = 210_000;
    private static final int BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();

    private PasswordHash() { }

    /** A hash of {@code password} with a fresh random salt. */
    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return PREFIX + ITERATIONS + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(derive(password, salt, ITERATIONS));
    }

    public static boolean isHash(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** True when {@code given} is the password that {@code stored} (plain or hashed) stands for. Constant time in the secret parts. */
    public static boolean matches(String stored, String given) {
        if (stored == null || given == null) return false;
        if (!isHash(stored)) return MessageDigest.isEqual(sha(given), sha(stored));
        String[] p = stored.split("\\$");
        if (p.length != 4) return false;
        try {
            int iterations = Integer.parseInt(p[1]);
            if (iterations < 10_000 || iterations > 5_000_000) return false;
            byte[] want = Base64.getDecoder().decode(p[3]);
            return MessageDigest.isEqual(want, derive(given, Base64.getDecoder().decode(p[2]), iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, BITS)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); // equal length, so the compare does not leak length
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
