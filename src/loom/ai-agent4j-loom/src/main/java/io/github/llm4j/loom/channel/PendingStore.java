package io.github.llm4j.loom.channel;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The questions of a run store, one JSON file per code under {@code <store>/channel/questions}, written atomically. Every change goes through
 * {@link #locked}, which holds a lock across threads and processes, so two answers at once admit one.
 */
public final class PendingStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Path root;
    private final Path questions;

    public PendingStore(Path store) {
        this.root = store.toAbsolutePath().normalize().resolve("channel");
        this.questions = root.resolve("questions");
    }

    /** {@code <store>/channel}: the questions, the audit log and the channel's own state. */
    public Path root() {
        return root;
    }

    public boolean exists() {
        return Files.isDirectory(questions);
    }

    public <T> T locked(Supplier<T> action) {
        ReentrantLock jvm = LOCKS.computeIfAbsent(root, p -> new ReentrantLock());
        jvm.lock();
        try {
            Files.createDirectories(root);
            try (FileChannel ch = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = ch.lock()) {
                return action.get();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            jvm.unlock();
        }
    }

    public Optional<Pending> get(String code) {
        if (code == null || !Codes.looksLikeOne(code)) return Optional.empty();
        Path f = questions.resolve(code.toUpperCase(java.util.Locale.ROOT) + ".json");
        if (!Files.isRegularFile(f)) return Optional.empty();
        return Optional.of(read(f));
    }

    /** The question a step of a run already asked: runs are identified by their directory, which stays the same however they are resumed. */
    public Optional<Pending> find(String run, String step) {
        return all().stream().filter(p -> p.run().equals(run) && p.step().equals(step)).findFirst();
    }

    public List<Pending> all() {
        if (!Files.isDirectory(questions)) return List.of();
        try (Stream<Path> files = Files.list(questions)) {
            List<Pending> out = new ArrayList<>();
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    out.add(read(f));
                } catch (RuntimeException e) {
                    // a half-written or foreign file is not a question
                }
            }
            out.sort(Comparator.comparing(Pending::createdAt).thenComparing(Pending::code));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Pending> open() {
        return all().stream().filter(Pending::open).toList();
    }

    /** Writes a question (atomically). Callers hold {@link #locked}. */
    public void put(Pending p) {
        try {
            Files.createDirectories(questions);
            Path tmp = Files.createTempFile(questions, ".q", ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(p.toMap()));
            Files.move(tmp, questions.resolve(p.code() + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A code no question of this store uses. */
    public String freshCode() {
        return Codes.fresh(c -> Files.exists(questions.resolve(c + ".json")));
    }

    private static Pending read(Path f) {
        try {
            return Pending.fromMap(JSON.readValue(f.toFile(), new TypeReference<Map<String, Object>>() { }));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
