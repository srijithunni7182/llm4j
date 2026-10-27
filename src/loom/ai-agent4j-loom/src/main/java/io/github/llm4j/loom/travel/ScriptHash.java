package io.github.llm4j.loom.travel;

import io.github.llm4j.loom.graph.ImportClosure;
import io.github.llm4j.loom.graph.ImportClosureLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/**
 * A fingerprint of a script and the files it imports, recorded with a run so that a later
 * explanation can tell whether the script is still the one that ran.
 */
public final class ScriptHash {

    private ScriptHash() {}

    /**
     * SHA-256 of the entry script and every file it imports, each named by its place relative to
     * the entry.
     */
    public static String of(Path entry) {
        Path root = entry.toAbsolutePath().normalize().getParent();
        TreeMap<String, Path> files = new TreeMap<>();
        files.put(entry.getFileName().toString(), entry.toAbsolutePath().normalize());
        ImportClosure closure = new ImportClosureLoader().load(entry);
        List<Path> all = closure.discovery();
        for (Path p : all) files.put(root.relativize(p).toString().replace('\\', '/'), p);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (var e : files.entrySet()) {
                digest.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(e.getValue()));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(
                    "Cannot fingerprint " + entry + ": " + e.getMessage(), e);
        }
    }
}
