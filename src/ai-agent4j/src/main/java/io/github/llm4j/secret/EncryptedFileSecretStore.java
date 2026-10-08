package io.github.llm4j.secret;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Secrets in one encrypted file whose location and master key the consumer chooses. Nothing is defaulted: no path, no key source.
 *
 * <p><b>Format.</b> One JSON document naming the format, version, key-derivation function (PBKDF2-HMAC-SHA256, with its iteration count and salt)
 * and cipher (AES-256-GCM, with its nonce), and the encrypted payload. The header fields are the GCM <em>additional authenticated data</em>, so
 * changing any of them (for example lowering the iteration count) fails authentication. The payload, secret names included, is encrypted;
 * every write re-encrypts it under a fresh random nonce.
 *
 * <p><b>Writes</b> go to a temporary file in the same directory, are flushed to disk and moved into place atomically. The file's existing
 * permissions (POSIX) or ACL are carried over to the replacement. Before writing, the file is compared with what was loaded: if another process
 * changed it, the write fails rather than lose that change ({@link #reload()} to take it in).
 *
 * <p><b>Reads</b> check the file's size and modification time and re-read it when it changed, so a secret rotated by another process is picked up
 * without a restart.
 *
 * <p><b>Protecting the file is the consumer's responsibility.</b> The library does not choose the directory or require a particular ACL. By default
 * it logs one warning when a POSIX file is readable by group or others; {@link Options#requirePrivateFile()} makes that an error.
 *
 * <p>Thread-safe within a process. One writing process at a time is assumed.
 */
public final class EncryptedFileSecretStore implements SecretStore {

    private static final Logger log = LoggerFactory.getLogger(EncryptedFileSecretStore.class);

    public static final int DEFAULT_ITERATIONS = 600_000;
    public static final int MIN_ITERATIONS = 100_000;
    static final int MAX_ITERATIONS = 10_000_000;
    private static final String FORMAT = "llm4j-secrets";
    private static final int VERSION = 1;
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final String CIPHER = "AES-256-GCM";
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Options for opening or creating a store. */
    public static final class Options {
        private int iterations = DEFAULT_ITERATIONS;
        private boolean requirePrivateFile;

        private Options() { }

        /** PBKDF2 iterations for a newly created or re-keyed file (default 600 000, minimum 100 000). An existing file keeps its own. */
        public Options kdfIterations(int iterations) {
            if (iterations < MIN_ITERATIONS) throw new IllegalArgumentException("at least " + MIN_ITERATIONS + " iterations");
            this.iterations = iterations;
            return this;
        }

        /** Refuse a file that group or others can read (POSIX file systems only). Off by default: protecting the file is the consumer's job. */
        public Options requirePrivateFile() {
            this.requirePrivateFile = true;
            return this;
        }

        public static Options defaults() {
            return new Options();
        }

        public int iterations() {
            return iterations;
        }
    }

    private static final class Entry {
        char[] value;
        SecretMetadata metadata;

        Entry(char[] value, SecretMetadata metadata) {
            this.value = value;
            this.metadata = metadata;
        }
    }

    private final Path file;
    private final Options options;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private MasterKey master;
    private byte[] key;
    private byte[] salt;
    private int iterations;
    private final Map<String, Entry> secrets = new TreeMap<>();
    private byte[] loadedDigest;
    private long loadedSize;
    private long loadedMillis;
    private boolean closed;
    /** Test seam: runs after the temporary file is written and before it replaces the real one. */
    Runnable beforeMoveHook;

    private EncryptedFileSecretStore(Path file, MasterKey master, Options options) {
        this.file = file.toAbsolutePath();
        this.master = master;
        this.options = options;
    }

    /** Creates a new, empty store; fails if the file already exists. */
    public static EncryptedFileSecretStore create(Path file, MasterKey master) {
        return create(file, master, Options.defaults());
    }

    public static EncryptedFileSecretStore create(Path file, MasterKey master, Options options) {
        EncryptedFileSecretStore s = new EncryptedFileSecretStore(requireNonNull(file), requireNonNull(master), options);
        if (Files.exists(s.file, LinkOption.NOFOLLOW_LINKS) || Files.exists(s.file)) {
            throw new SecretStoreException("the secret file " + s.file + " already exists; open it, or choose another path");
        }
        s.initializeNew();
        return s;
    }

    /** Opens an existing store; fails if the file is missing, the master key is wrong or the file was damaged or tampered with. */
    public static EncryptedFileSecretStore open(Path file, MasterKey master) {
        return open(file, master, Options.defaults());
    }

    public static EncryptedFileSecretStore open(Path file, MasterKey master, Options options) {
        EncryptedFileSecretStore s = new EncryptedFileSecretStore(requireNonNull(file), requireNonNull(master), options);
        if (!Files.exists(s.file)) throw new SecretStoreException("the secret file " + s.file + " does not exist");
        s.checkPermissions();
        s.load(true);
        return s;
    }

    /** Opens the store, or creates it if the file does not exist. */
    public static EncryptedFileSecretStore openOrCreate(Path file, MasterKey master) {
        return openOrCreate(file, master, Options.defaults());
    }

    public static EncryptedFileSecretStore openOrCreate(Path file, MasterKey master, Options options) {
        return Files.exists(requireNonNull(file)) ? open(file, master, options) : create(file, master, options);
    }

    private static <T> T requireNonNull(T value) {
        return java.util.Objects.requireNonNull(value);
    }

    /** The file this store reads and writes. */
    public Path file() {
        return file;
    }

    // ---- SecretStore --------------------------------------------------------------------------------------------

    @Override
    public String resolve(String name) {
        refreshIfChanged();
        lock.readLock().lock();
        try {
            ensureOpen();
            Entry e = name == null ? null : secrets.get(name);
            if (e == null) throw new SecretNotFoundException(String.valueOf(name));
            return new String(e.value);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean contains(String name) {
        refreshIfChanged();
        lock.readLock().lock();
        try {
            ensureOpen();
            return name != null && secrets.containsKey(name);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Set<String> names() {
        refreshIfChanged();
        lock.readLock().lock();
        try {
            ensureOpen();
            return Collections.unmodifiableSet(new TreeSet<>(secrets.keySet()));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<SecretMetadata> metadata(String name) {
        refreshIfChanged();
        lock.readLock().lock();
        try {
            ensureOpen();
            Entry e = name == null ? null : secrets.get(name);
            return e == null ? Optional.empty() : Optional.of(e.metadata);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void put(String name, char[] value, SecretMetadata metadata) {
        SecretNames.require(name);
        if (value == null || value.length == 0) throw new IllegalArgumentException("a secret's value must not be empty");
        lock.writeLock().lock();
        try {
            ensureOpen();
            requireUnchangedOnDisk();
            Entry previous = secrets.get(name);
            SecretMetadata m = (metadata == null ? SecretMetadata.NONE : metadata).withUpdatedAt(Instant.now());
            secrets.put(name, new Entry(value.clone(), m));
            try {
                save();
            } catch (RuntimeException e) {
                Entry failed = secrets.remove(name);
                if (failed != null) Arrays.fill(failed.value, '\0');
                if (previous != null) secrets.put(name, previous);
                throw e;
            }
            if (previous != null) Arrays.fill(previous.value, '\0');
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean remove(String name) {
        lock.writeLock().lock();
        try {
            ensureOpen();
            requireUnchangedOnDisk();
            Entry previous = name == null ? null : secrets.remove(name);
            if (previous == null) return false;
            try {
                save();
            } catch (RuntimeException e) {
                secrets.put(name, previous);
                throw e;
            }
            Arrays.fill(previous.value, '\0');
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Takes in changes another process made to the file. */
    public void reload() {
        lock.writeLock().lock();
        try {
            ensureOpen();
            load(false);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Re-encrypts the store under a new master key and a new salt. The old key stops working. */
    public void rekey(MasterKey newMaster) {
        requireNonNull(newMaster);
        lock.writeLock().lock();
        try {
            ensureOpen();
            requireUnchangedOnDisk();
            byte[] oldKey = key;
            byte[] oldSalt = salt;
            MasterKey oldMaster = master;
            byte[] newSalt = randomBytes(SALT_BYTES);
            byte[] newKey = derive(newMaster, newSalt, Math.max(iterations, options.iterations));
            int oldIterations = iterations;
            key = newKey;
            salt = newSalt;
            iterations = Math.max(iterations, options.iterations);
            master = newMaster;
            try {
                save();
            } catch (RuntimeException e) {
                key = oldKey;
                salt = oldSalt;
                iterations = oldIterations;
                master = oldMaster;
                Arrays.fill(newKey, (byte) 0);
                throw e;
            }
            Arrays.fill(oldKey, (byte) 0);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            if (key != null) Arrays.fill(key, (byte) 0);
            for (Entry e : secrets.values()) Arrays.fill(e.value, '\0');
            secrets.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public String toString() {
        return "EncryptedFileSecretStore[" + file + "]";
    }

    // ---- file handling ------------------------------------------------------------------------------------------

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("this secret store is closed");
    }

    private void initializeNew() {
        lock.writeLock().lock();
        try {
            salt = randomBytes(SALT_BYTES);
            iterations = options.iterations;
            key = derive(master, salt, iterations);
            Path dir = file.getParent();
            if (dir != null && !Files.isDirectory(dir)) {
                throw new SecretStoreException("the directory " + dir + " does not exist; the consumer creates and protects it");
            }
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void checkPermissions() {
        try {
            if (!Files.getFileStore(file).supportsFileAttributeView("posix")) return;
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            boolean open = perms.stream().anyMatch(p -> p.name().startsWith("GROUP") || p.name().startsWith("OTHERS"));
            if (!open) return;
            if (options.requirePrivateFile) {
                throw new SecretStoreException("the secret file " + file + " is readable by group or others ("
                        + PosixFilePermissions.toString(perms) + "); restrict it, for example to rw-------");
            }
            log.warn("the secret file {} is readable by group or others ({}); protecting it is the consumer's responsibility",
                    file, PosixFilePermissions.toString(perms));
        } catch (IOException | UnsupportedOperationException e) {
            // a file system that cannot say: nothing to check
        }
    }

    private void refreshIfChanged() {
        if (!changedOnDisk()) return;
        lock.writeLock().lock();
        try {
            ensureOpen();
            if (changedOnDisk()) load(false);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean changedOnDisk() {
        lock.readLock().lock();
        try {
            if (closed) return false;
            try {
                return Files.size(file) != loadedSize || Files.getLastModifiedTime(file).toMillis() != loadedMillis;
            } catch (IOException e) {
                return true; // gone or unreadable: load() reports it
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    private void requireUnchangedOnDisk() {
        byte[] now;
        try {
            now = Files.exists(file) ? sha256(Files.readAllBytes(file)) : null;
        } catch (IOException e) {
            throw new SecretStoreException("cannot read " + file + ": " + e.getMessage(), e);
        }
        if (!java.util.Arrays.equals(now, loadedDigest)) {
            throw new SecretStoreException("the secret file " + file + " was changed by someone else since it was loaded; call reload() and try again");
        }
    }

    /** Reads, authenticates and decrypts the file. */
    private void load(boolean firstOpen) {
        byte[] bytes;
        long size;
        long millis;
        try {
            bytes = Files.readAllBytes(file);
            size = Files.size(file);
            millis = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new SecretStoreException("cannot read the secret file " + file + ": " + e.getMessage(), e);
        }
        JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (IOException e) {
            throw new SecretStoreException("the secret file " + file + " is not a secret store (not JSON)");
        }
        if (root == null || !root.isObject() || !FORMAT.equals(text(root, "format"))) {
            throw new SecretStoreException("the secret file " + file + " is not a secret store (unknown format)");
        }
        if (root.path("version").asInt(-1) != VERSION) {
            throw new SecretStoreException("the secret file " + file + " has version " + root.path("version").asText() + "; this library reads version " + VERSION);
        }
        JsonNode kdf = root.path("kdf");
        JsonNode cipher = root.path("cipher");
        if (!KDF.equals(text(kdf, "alg")) || !CIPHER.equals(text(cipher, "alg"))) {
            throw new SecretStoreException("the secret file " + file + " uses an unsupported key-derivation or cipher algorithm");
        }
        int fileIterations = kdf.path("iterations").asInt(-1);
        if (fileIterations < MIN_ITERATIONS || fileIterations > MAX_ITERATIONS) {
            throw new SecretStoreException("the secret file " + file + " has an unacceptable key-derivation iteration count");
        }
        byte[] fileSalt = decode(text(kdf, "salt"), file);
        byte[] nonce = decode(text(cipher, "nonce"), file);
        byte[] data = decode(text(root, "data"), file);
        if (fileSalt.length < SALT_BYTES || fileSalt.length > 64 || nonce.length != NONCE_BYTES || data.length < TAG_BITS / 8) {
            throw new SecretStoreException("the secret file " + file + " is damaged");
        }
        byte[] aad = aad(fileIterations, text(kdf, "salt"));
        byte[] useKey = key;
        boolean derived = false;
        if (key == null || !Arrays.equals(fileSalt, salt) || fileIterations != iterations) {
            useKey = derive(master, fileSalt, fileIterations);
            derived = true;
        }
        byte[] plain;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(useKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad);
            plain = c.doFinal(data);
        } catch (GeneralSecurityException e) {
            if (derived) Arrays.fill(useKey, (byte) 0);
            throw new SecretStoreException("cannot decrypt " + file + ": wrong master key or the file is damaged");
        }
        Map<String, Entry> parsed = new TreeMap<>();
        try {
            JsonNode payload = JSON.readTree(plain);
            JsonNode all = payload.path("secrets");
            Iterator<Map.Entry<String, JsonNode>> it = all.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                if (!SecretNames.isValid(f.getKey())) throw new SecretStoreException("the secret file " + file + " is damaged (bad secret name)");
                JsonNode n = f.getValue();
                Set<String> hosts = new LinkedHashSet<>();
                for (JsonNode h : n.path("allowedHosts")) hosts.add(h.asText());
                String updated = text(n, "updatedAt");
                SecretMetadata m = new SecretMetadata(hosts, n.hasNonNull("description") ? n.get("description").asText() : null,
                        updated == null ? null : Instant.parse(updated));
                parsed.put(f.getKey(), new Entry(n.path("value").asText().toCharArray(), m));
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof SecretStoreException s) throw s;
            throw new SecretStoreException("the secret file " + file + " is damaged");
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        // success: swap in
        for (Entry e : secrets.values()) Arrays.fill(e.value, '\0');
        secrets.clear();
        secrets.putAll(parsed);
        if (derived) {
            if (key != null) Arrays.fill(key, (byte) 0);
            key = useKey;
            salt = fileSalt;
            iterations = fileIterations;
        }
        loadedDigest = sha256(bytes);
        loadedSize = size;
        loadedMillis = millis;
    }

    /** Encrypts and atomically replaces the file. Called under the write lock. */
    private void save() {
        ObjectNode payload = JSON.createObjectNode();
        ObjectNode all = payload.putObject("secrets");
        for (Map.Entry<String, Entry> e : secrets.entrySet()) {
            ObjectNode n = all.putObject(e.getKey());
            n.put("value", new String(e.getValue().value));
            ArrayNode hosts = n.putArray("allowedHosts");
            e.getValue().metadata.allowedHosts().forEach(hosts::add);
            if (e.getValue().metadata.description() != null) n.put("description", e.getValue().metadata.description());
            if (e.getValue().metadata.updatedAt() != null) n.put("updatedAt", e.getValue().metadata.updatedAt().toString());
        }
        byte[] plain;
        byte[] cipherText;
        byte[] nonce = randomBytes(NONCE_BYTES);
        String saltB64 = Base64.getEncoder().encodeToString(salt);
        try {
            plain = JSON.writeValueAsBytes(payload);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad(iterations, saltB64));
            cipherText = c.doFinal(plain);
            Arrays.fill(plain, (byte) 0);
        } catch (IOException | GeneralSecurityException e) {
            throw new SecretStoreException("cannot encrypt the secret store: " + e.getMessage(), e);
        }
        ObjectNode doc = JSON.createObjectNode();
        doc.put("format", FORMAT);
        doc.put("version", VERSION);
        ObjectNode kdf = doc.putObject("kdf");
        kdf.put("alg", KDF);
        kdf.put("iterations", iterations);
        kdf.put("salt", saltB64);
        ObjectNode cipher = doc.putObject("cipher");
        cipher.put("alg", CIPHER);
        cipher.put("nonce", Base64.getEncoder().encodeToString(nonce));
        doc.put("data", Base64.getEncoder().encodeToString(cipherText));
        byte[] bytes;
        try {
            bytes = (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(doc) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SecretStoreException("cannot write the secret store: " + e.getMessage(), e);
        }
        writeAtomically(bytes);
        try {
            loadedDigest = sha256(bytes);
            loadedSize = Files.size(file);
            loadedMillis = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new SecretStoreException("cannot read back " + file + ": " + e.getMessage(), e);
        }
    }

    private void writeAtomically(byte[] bytes) {
        Path dir = file.getParent();
        Path temp = null;
        try {
            boolean posix = Files.getFileStore(dir).supportsFileAttributeView("posix");
            temp = posix
                    ? Files.createTempFile(dir, ".secrets-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(dir, ".secrets-", ".tmp");
            if (Files.exists(file)) carryOverAccess(file, temp, posix);
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ch.write(ByteBuffer.wrap(bytes));
                ch.force(true);
            }
            if (beforeMoveHook != null) beforeMoveHook.run();
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException e) {
            throw new SecretStoreException("cannot write the secret file " + file + ": " + e.getMessage(), e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // leaving a temporary file is better than hiding the real failure
                }
            }
        }
    }

    /** The replacement keeps the access the consumer gave the existing file. */
    private static void carryOverAccess(Path from, Path to, boolean posix) {
        try {
            if (posix) {
                Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from));
            } else {
                AclFileAttributeView source = Files.getFileAttributeView(from, AclFileAttributeView.class);
                AclFileAttributeView target = Files.getFileAttributeView(to, AclFileAttributeView.class);
                if (source != null && target != null) target.setAcl(source.getAcl());
            }
        } catch (IOException | UnsupportedOperationException e) {
            log.debug("could not carry the file's access settings over to its replacement: {}", e.getMessage());
        }
    }

    // ---- crypto helpers -----------------------------------------------------------------------------------------

    private static byte[] derive(MasterKey master, byte[] salt, int iterations) {
        char[] pass = master.passphrase();
        PBEKeySpec spec = new PBEKeySpec(pass, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new SecretStoreException("key derivation failed: " + e.getMessage(), e);
        } finally {
            spec.clearPassword();
            Arrays.fill(pass, '\0');
        }
    }

    /** The header, as the cipher authenticates it. */
    private static byte[] aad(int iterations, String saltB64) {
        return (FORMAT + "|" + VERSION + "|" + KDF + "|" + iterations + "|" + saltB64 + "|" + CIPHER).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    private static byte[] decode(String b64, Path file) {
        if (b64 == null) throw new SecretStoreException("the secret file " + file + " is damaged (missing field)");
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new SecretStoreException("the secret file " + file + " is damaged");
        }
    }
}
