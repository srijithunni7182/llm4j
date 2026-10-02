package io.github.llm4j.loom.autonomy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Levels in one file per decision, {@code <dir>/<decision>/levels.json}, rewritten atomically (a temp file moved over it) under a lock on a
 * sibling file, so a crash leaves the old state or the new, never half. Every call reads the file, so a command and a run in other
 * processes see each other's changes.
 */
public class FileLevelStore implements LevelStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** File locks keep processes apart; this keeps the threads of one process apart (a lock cannot be held twice in a JVM). */
    private static final Object JVM = new Object();
    private final Path dir;

    public FileLevelStore(Path dir) {
        this.dir = dir;
    }

    private Path file(String decision) {
        return dir.resolve(decision.equals("*") ? "_all" : Names.check(decision)).resolve("levels.json");
    }

    private interface Change<T> {
        T apply(Map<String, Object> doc) throws IOException;
    }

    private <T> T locked(String decision, boolean write, Change<T> change) {
        Path file = file(decision);
        synchronized (JVM) {
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel lockFile = FileChannel.open(file.resolveSibling("levels.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = lockFile.lock()) {
                Map<String, Object> doc = Files.exists(file) ? JSON.readValue(file.toFile(), new TypeReference<Map<String, Object>>() { }) : new LinkedHashMap<>();
                int before = doc.hashCode();
                T result = change.apply(doc);
                if (write && doc.hashCode() != before) {
                    Path tmp = file.resolveSibling("levels.json.tmp");
                    JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), doc);
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                return result;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot use the level store " + file, e);
        }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scopesOf(Map<String, Object> doc) {
        return (Map<String, Object>) doc.computeIfAbsent("scopes", k -> new LinkedHashMap<String, Object>());
    }

    static Map<String, Object> toMap(LevelState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", s.level().word());
        m.put("epoch", s.epoch());
        m.put("identity", s.identity());
        m.put("forced", s.forced());
        m.put("since", s.since() == null ? null : s.since().toString());
        m.put("reason", s.reason());
        m.put("version", s.version());
        return m;
    }

    @SuppressWarnings("unchecked")
    static LevelState fromMap(Object o) {
        Map<String, Object> m = (Map<String, Object>) o;
        return new LevelState(Level.of((String) m.get("level")), ((Number) m.get("epoch")).intValue(), (String) m.get("identity"),
                Boolean.TRUE.equals(m.get("forced")), m.get("since") == null ? null : Instant.parse((String) m.get("since")), (String) m.get("reason"),
                ((Number) m.get("version")).intValue());
    }

    @Override
    public Optional<LevelState> get(String decision, String scope) {
        return locked(decision, false, doc -> Optional.ofNullable(scopesOf(doc).get(scope)).map(FileLevelStore::fromMap));
    }

    @Override
    public boolean compareAndSet(String decision, String scope, LevelState expected, LevelState next) {
        return locked(decision, true, doc -> {
            Map<String, Object> scopes = scopesOf(doc);
            Object current = scopes.get(scope);
            if (current == null ? expected != null : (expected == null || fromMap(current).version() != expected.version())) return false;
            scopes.put(scope, toMap(next));
            return true;
        });
    }

    @Override
    public Map<String, LevelState> scopes(String decision) {
        return locked(decision, false, doc -> {
            Map<String, LevelState> out = new LinkedHashMap<>();
            scopesOf(doc).forEach((k, v) -> out.put(k, fromMap(v)));
            return out;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Freeze> freeze(String decision) {
        return locked(decision, false, doc -> {
            Map<String, Object> f = (Map<String, Object>) doc.get("freeze");
            return f == null ? Optional.empty() : Optional.of(new Freeze((String) f.get("reason"), Instant.parse((String) f.get("at"))));
        });
    }

    @Override
    public void setFreeze(String decision, Freeze freeze) {
        locked(decision, true, doc -> {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("reason", freeze.reason());
            f.put("at", freeze.at().toString());
            doc.put("freeze", f);
            return null;
        });
    }

    @Override
    public void clearFreeze(String decision) {
        locked(decision, true, doc -> doc.remove("freeze"));
    }
}
