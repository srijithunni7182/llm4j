package io.github.llm4j.loom.loop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.runtime.RunJournal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Read-only access to a store of past runs. There is no method here that writes, deletes or locks:
 * the store may be written by runs in progress, and each file is read in one piece (the journal is
 * replaced atomically by the run, so a reader sees a whole old version or a whole new one).
 */
public final class StoreReader {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String RUN_FILE = "run.json";
    static final String JOURNAL_FILE = "journal.json";

    private StoreReader() {}

    /** Every run in a store (run folders directly in it, or under {@code runs/}), newest first. */
    public static List<RunRecord> list(Path store) throws IOException {
        List<Path> dirs = new ArrayList<>();
        for (Path base : List.of(store, store.resolve("runs"))) {
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> s = Files.list(base)) {
                s.filter(d -> Files.isRegularFile(d.resolve(RUN_FILE))).forEach(dirs::add);
            }
        }
        List<RunRecord> out = new ArrayList<>();
        for (Path d : dirs) {
            try {
                out.add(open(d));
            } catch (IOException | RuntimeException e) {
                // a run being written, or pruned, or not one of ours: it is not a candidate
            }
        }
        out.sort(Comparator.comparing(RunRecord::modified).reversed());
        return out;
    }

    /** One run, from its folder. */
    public static RunRecord open(Path dir) throws IOException {
        Path run = dir.toAbsolutePath().normalize();
        JsonNode spec = JSON.readTree(Files.readAllBytes(run.resolve(RUN_FILE)));
        Map<String, String> inputs = new LinkedHashMap<>();
        JsonNode in = spec.path("inputs");
        in.fieldNames().forEachRemaining(k -> inputs.put(k, in.get(k).asText()));
        Map<String, RunJournal.Entry> journal = new LinkedHashMap<>();
        Path jf = run.resolve(JOURNAL_FILE);
        Instant modified = Files.getLastModifiedTime(run.resolve(RUN_FILE)).toInstant();
        if (Files.isRegularFile(jf)) {
            journal.putAll(
                    JSON.readValue(
                            Files.readAllBytes(jf),
                            new TypeReference<Map<String, RunJournal.Entry>>() {}));
            Instant jm = Files.getLastModifiedTime(jf).toInstant();
            if (jm.isAfter(modified)) modified = jm;
        }
        String script = spec.path("script").asText(null);
        if (script == null || script.isBlank())
            throw new IOException(run.getFileName() + " records no script");
        Path scriptPath = Path.of(script);
        if (!scriptPath.isAbsolute()) scriptPath = run.resolve(scriptPath).normalize();
        return new RunRecord(
                run.getFileName().toString(),
                run,
                scriptPath,
                spec.path("workflow").asText("Main"),
                inputs,
                spec.hasNonNull("scriptHash") ? spec.get("scriptHash").asText() : null,
                modified,
                journal);
    }

    /**
     * Finds a run by folder or by id in a store. Throws with the ids that exist when it is neither.
     */
    public static RunRecord find(String idOrDir, Path store) throws IOException {
        Path direct = Path.of(idOrDir);
        if (Files.isRegularFile(direct.resolve(RUN_FILE))) return open(direct);
        if (store != null) {
            for (RunRecord r : list(store)) if (r.id().equals(idOrDir)) return r;
        }
        List<String> ids =
                store == null
                        ? List.of()
                        : list(store).stream().map(RunRecord::id).limit(8).toList();
        throw new IllegalArgumentException(
                "no run \""
                        + idOrDir
                        + "\""
                        + (ids.isEmpty() ? "" : "; runs in the store include " + ids));
    }

    /**
     * A fingerprint of everything in a store (every file's name and bytes), to show that a command
     * left it as it found it.
     */
    public static String fingerprint(Path store) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files;
            try (Stream<Path> s = Files.walk(store)) {
                files = s.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path f : files) {
                digest.update(store.relativize(f).toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(f));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
