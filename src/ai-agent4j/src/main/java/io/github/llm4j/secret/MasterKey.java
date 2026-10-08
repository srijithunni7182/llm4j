package io.github.llm4j.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The passphrase that protects an {@link EncryptedFileSecretStore}. The consumer supplies it and chooses where it comes from: nothing is
 * defaulted or discovered. The store stretches it with PBKDF2 before use.
 *
 * <p>How safe the store is depends on how safe this passphrase is: one in a shell history, or a key file next to the store, defeats it.
 */
public final class MasterKey {

    private final Supplier<char[]> source;
    private final String description;
    private final Runnable wiper;
    private volatile boolean destroyed;

    private MasterKey(Supplier<char[]> source, String description, Runnable wiper) {
        this.source = source;
        this.description = description;
        this.wiper = wiper;
    }

    private MasterKey(Supplier<char[]> source, String description) {
        this(source, description, () -> { });
    }

    /** A passphrase held in memory (copied; the caller may wipe its array). */
    public static MasterKey of(char[] passphrase) {
        Objects.requireNonNull(passphrase, "passphrase");
        if (passphrase.length == 0) throw new IllegalArgumentException("the master key must not be empty");
        char[] held = passphrase.clone();
        return new MasterKey(held::clone, "passphrase", () -> Arrays.fill(held, '\0'));
    }

    /** The first line of a file: read each time the store needs it, so a rotated key file is honoured. The consumer protects the file. */
    public static MasterKey fromFile(Path file) {
        Objects.requireNonNull(file, "file");
        return new MasterKey(() -> {
            try {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                int end = 0;
                while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') end++;
                String line = text.substring(0, end);
                if (line.isEmpty()) throw new SecretStoreException("the master key file " + file + " is empty");
                return line.toCharArray();
            } catch (IOException e) {
                throw new SecretStoreException("cannot read the master key file " + file + ": " + e.getMessage(), e);
            }
        }, "key file " + file);
    }

    /** An environment variable the <em>consumer</em> names; there is no default variable. */
    public static MasterKey fromEnv(String variable) {
        return fromEnv(variable, System::getenv);
    }

    /** As {@link #fromEnv(String)} with an explicit environment (for tests and hosts that carry their own). */
    public static MasterKey fromEnv(String variable, Function<String, String> env) {
        Objects.requireNonNull(variable, "variable");
        return new MasterKey(() -> {
            String v = env.apply(variable);
            if (v == null || v.isEmpty()) throw new SecretStoreException("the environment variable " + variable + " (the master key) is not set");
            return v.toCharArray();
        }, "environment variable " + variable);
    }

    /** Any source, called whenever the store needs the passphrase; the returned array is wiped after use. */
    public static MasterKey from(Supplier<char[]> source) {
        Objects.requireNonNull(source, "source");
        return new MasterKey(source, "supplier");
    }

    /** A fresh copy of the passphrase for the caller to use and wipe. */
    char[] passphrase() {
        if (destroyed) throw new IllegalStateException("this master key was destroyed");
        char[] p = source.get();
        if (p == null || p.length == 0) throw new SecretStoreException("the master key is empty");
        return p;
    }

    /** Wipes a passphrase held in memory; later use fails. */
    public void destroy() {
        destroyed = true;
        wiper.run();
    }

    @Override
    public String toString() {
        return "MasterKey[" + description + "]";
    }
}
