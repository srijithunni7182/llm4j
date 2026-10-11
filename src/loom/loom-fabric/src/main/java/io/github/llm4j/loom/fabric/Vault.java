package io.github.llm4j.loom.fabric;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Fabric's own small encrypted store: the admin password and each client's key, AES-256-GCM, one file. The master key comes from a
 * file or an environment variable, never from the command line, and is stretched with PBKDF2. It holds the same kind of secret as a
 * Loom secret store and has the same trust model (encrypted at rest, the master key kept elsewhere), but it is Fabric's own format so
 * that the server needs no Loom runtime.
 *
 * <p>Names: {@code admin.password} and {@code client.<name>}. Values never appear in {@code toString}, in logs or in exceptions.
 */
public final class Vault {

    public static final String ADMIN = "admin.password";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 200_000;

    private final Path file;
    private final SecretKey key;
    private final byte[] salt;
    private final Map<String, String> entries = new LinkedHashMap<>(); // name to base64(iv + ciphertext)

    private Vault(Path file, byte[] salt, SecretKey key) {
        this.file = file;
        this.salt = salt;
        this.key = key;
    }

    /** Creates a new, empty vault; refuses to overwrite one. */
    public static Vault create(Path file, String masterKey) {
        if (Files.exists(file)) throw new IllegalStateException("there is already a vault at " + file);
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        Vault v = new Vault(file, salt, derive(masterKey, salt));
        v.save();
        return v;
    }

    /** Opens an existing vault. A wrong master key is found on the first read, as an {@link IllegalStateException}. */
    public static Vault open(Path file, String masterKey) {
        try {
            Map<String, Object> raw = JSON.readValue(Files.readAllBytes(file), new TypeReference<>() { });
            byte[] salt = Base64.getDecoder().decode((String) raw.get("salt"));
            Vault v = new Vault(file, salt, derive(masterKey, salt));
            @SuppressWarnings("unchecked")
            Map<String, String> stored = (Map<String, String>) raw.get("entries");
            if (stored != null) v.entries.putAll(stored);
            return v;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the vault " + file + ": " + e.getMessage(), e);
        }
    }

    private static SecretKey derive(String masterKey, byte[] salt) {
        if (masterKey == null || masterKey.length() < 16) throw new IllegalArgumentException("the master key must be at least 16 characters");
        try {
            byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(masterKey.toCharArray(), salt, ITERATIONS, 256)).getEncoded();
            return new SecretKeySpec(k, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void put(String name, String value) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            c.updateAAD(name.getBytes(StandardCharsets.UTF_8)); // a value cannot be moved to another name
            byte[] ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            entries.put(name, Base64.getEncoder().encodeToString(all));
            save();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The value, or null when there is none. */
    public synchronized String get(String name) {
        String stored = entries.get(name);
        if (stored == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            c.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot decrypt '" + name + "': wrong master key, or the vault was changed");
        }
    }

    public synchronized boolean remove(String name) {
        boolean had = entries.remove(name) != null;
        if (had) save();
        return had;
    }

    public synchronized Set<String> names() {
        return new TreeSet<>(entries.keySet());
    }

    private void save() {
        try {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("v", 1);
            raw.put("salt", Base64.getEncoder().encodeToString(salt));
            raw.put("entries", entries);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(raw));
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "Vault[" + file + ", " + entries.size() + " entries]";
    }

    /** A random key for a client: 32 random bytes, URL-safe, with a prefix so it is recognisable in a secret store. */
    public static String newClientKey() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return "lfk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** A random master key, for {@code fabric init} when none is given. */
    public static String newMasterKey() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
