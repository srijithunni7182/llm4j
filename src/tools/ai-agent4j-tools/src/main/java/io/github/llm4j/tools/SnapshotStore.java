package io.github.llm4j.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Snapshots of a workspace, content-addressed on the JDK (D11, with the D10 guidance): each file's
 * bytes are stored once under their SHA-256, and a snapshot is a manifest of paths and hashes. A
 * restore checks every stored file's hash before it changes anything, so a store that was changed
 * since fails loudly instead of putting back the wrong bytes.
 */
final class SnapshotStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    /** One stored file: its hash and size. */
    record Stored(String sha, long size) {}

    /** A snapshot: its name, when it was taken (an ordinal) and its files by relative path. */
    record Manifest(String name, long taken, Map<String, Stored> files) {}

    private final Path store;
    private final int maxSnapshots;

    SnapshotStore(Path store, int maxSnapshots) {
        this.store = store;
        this.maxSnapshots = maxSnapshots;
    }

    static String checkName(String name) {
        if (name == null || !NAME.matcher(name).matches())
            throw new ToolRefusal(
                    "name must be letters, digits, '_', '.' or '-', starting with a letter or digit (at most 64)");
        return name;
    }

    private Path manifests() {
        return store.resolve("snapshots");
    }

    private Path objects() {
        return store.resolve("objects");
    }

    /**
     * Stores {@code files} (relative path to file) as {@code name}; nothing is kept if it fails.
     */
    Manifest save(String name, Map<String, Path> files) throws IOException {
        checkName(name);
        List<Manifest> existing = all();
        boolean replaces = existing.stream().anyMatch(m -> m.name().equals(name));
        if (!replaces && existing.size() >= maxSnapshots)
            throw new ToolRefusal(
                    "there are already "
                            + maxSnapshots
                            + " snapshots (snapshots:); reuse a name to replace one");
        Files.createDirectories(objects());
        Files.createDirectories(manifests());
        Map<String, Stored> stored = new TreeMap<>();
        for (Map.Entry<String, Path> f : files.entrySet()) {
            String sha = sha256(f.getValue());
            Path object = objects().resolve(sha);
            if (!Files.exists(object)) {
                Path temp = Files.createTempFile(objects(), "in-", ".tmp");
                try {
                    Files.copy(f.getValue(), temp, StandardCopyOption.REPLACE_EXISTING);
                    if (!sha.equals(sha256(temp)))
                        throw new ToolRefusal(
                                f.getKey() + " changed while the snapshot was taken; try again");
                    Files.move(temp, object, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
            stored.put(f.getKey(), new Stored(sha, Files.size(object)));
        }
        long taken = existing.stream().mapToLong(Manifest::taken).max().orElse(0) + 1;
        Manifest manifest = new Manifest(name, taken, stored);
        Path temp = Files.createTempFile(manifests(), "in-", ".tmp");
        try {
            JSON.writeValue(temp.toFile(), manifest);
            Files.move(
                    temp,
                    manifests().resolve(name + ".json"),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        if (replaces) collect();
        return manifest;
    }

    /** The named snapshot, or the latest when {@code name} is null. */
    Manifest load(String name) throws IOException {
        if (name != null) {
            checkName(name);
            Path file = manifests().resolve(name + ".json");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new ToolRefusal("there is no snapshot called " + name);
            return JSON.readValue(file.toFile(), Manifest.class);
        }
        return all().stream()
                .max(java.util.Comparator.comparingLong(Manifest::taken))
                .orElseThrow(
                        () ->
                                new ToolRefusal(
                                        "there is no snapshot yet: take one with action snapshot"));
    }

    /** The stored bytes of a file, checked against its hash; a mismatch names the file. */
    Path verified(String relative, Stored stored) throws IOException {
        Path object = objects().resolve(stored.sha());
        if (!Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)
                || !stored.sha().equals(sha256(object)))
            throw new ToolRefusal(
                    relative + " changed since it was stored, so the snapshot cannot be restored");
        return object;
    }

    private List<Manifest> all() throws IOException {
        if (!Files.isDirectory(manifests())) return List.of();
        List<Manifest> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(manifests())) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList())
                out.add(JSON.readValue(f.toFile(), Manifest.class));
        }
        return out;
    }

    /** Deletes stored files no snapshot names any more. */
    private void collect() throws IOException {
        Set<String> used = new HashSet<>();
        for (Manifest m : all()) m.files().values().forEach(s -> used.add(s.sha()));
        try (Stream<Path> objects = Files.list(objects())) {
            for (Path o : objects.toList())
                if (!used.contains(o.getFileName().toString())) Files.deleteIfExists(o);
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n = in.read(buffer); n > 0; n = in.read(buffer)) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("the JDK has no SHA-256", e);
        }
    }

    /** For a result: the files of a manifest as relative paths. */
    static Map<String, Object> describe(Manifest m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", m.name());
        out.put("files", m.files().size());
        out.put("bytes", m.files().values().stream().mapToLong(Stored::size).sum());
        return out;
    }
}
