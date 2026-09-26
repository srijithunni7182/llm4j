package io.github.llm4j.getviral.app.connect;

import io.github.llm4j.getviral.app.AppProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for social access/refresh tokens at rest. The key comes from
 * GETVIRAL_TOKEN_KEY (base64, 32 bytes — use Secret Manager in production). Locally, a key is
 * generated once into the data directory so development works without setup.
 */
@Component
public class TokenCipher {

    private static final Logger log = LoggerFactory.getLogger(TokenCipher.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public TokenCipher(AppProperties props) {
        this.key = new SecretKeySpec(loadKey(props), "AES");
    }

    private static byte[] loadKey(AppProperties props) {
        if (props.tokenKey() != null && !props.tokenKey().isBlank()) {
            byte[] bytes = Base64.getDecoder().decode(props.tokenKey().trim());
            if (bytes.length != 32) throw new IllegalStateException("GETVIRAL_TOKEN_KEY must be 32 bytes, base64-encoded");
            return bytes;
        }
        Path file = Path.of(props.dataDir(), "token.key");
        try {
            if (Files.exists(file)) return Base64.getDecoder().decode(Files.readString(file).trim());
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            Files.createDirectories(file.getParent());
            Files.writeString(file, Base64.getEncoder().encodeToString(bytes));
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX file system
            }
            log.warn("GETVIRAL_TOKEN_KEY not set — generated a local key at {}. Set GETVIRAL_TOKEN_KEY in production.", file);
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create a local token key", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return "v1:" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Token encryption failed", e);
        }
    }

    public String decrypt(String sealed) {
        if (sealed == null) return null;
        try {
            byte[] in = Base64.getDecoder().decode(sealed.substring(sealed.indexOf(':') + 1));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, in, 0, 12));
            return new String(cipher.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Token decryption failed (was GETVIRAL_TOKEN_KEY changed?)", e);
        }
    }
}
