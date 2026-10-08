package io.github.llm4j.loom.trigger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Triggers as files in a directory — one {@code triggers/<id>.json} each, written atomically — for a
 * single machine: the {@code weave} CLI, cron, systemd or launchd. A claim is a {@code <id>.claim} file
 * created exclusively, so two processes can never both fire a trigger; {@code tick.lock} serialises ticks.
 */
public class FileTriggerStore implements TriggerStore {

    private final Path dir;
    private final Path triggers;

    public FileTriggerStore(Path dir) {
        this.dir = dir;
        this.triggers = dir.resolve("triggers");
        try {
            Files.createDirectories(triggers);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create trigger store at " + dir, e);
        }
    }

    public Path dir() {
        return dir;
    }

    private Path file(String id) {
        return triggers.resolve(URLEncoder.encode(id, StandardCharsets.UTF_8) + ".json");
    }

    Path claimFile(String id) {
        return triggers.resolve(URLEncoder.encode(id, StandardCharsets.UTF_8) + ".claim");
    }

    @Override
    public synchronized void upsert(Trigger trigger) {
        write(file(trigger.id()), TriggerCodec.toJson(trigger.withClaim(null, null)));
        try {
            Files.deleteIfExists(claimFile(trigger.id()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Trigger> get(String id) {
        Path f = file(id);
        String json;
        try {
            json = Files.readString(f);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Trigger t = TriggerCodec.fromJson(json);
        String[] claim = readClaim(id);
        return Optional.of(claim == null ? t : t.withClaim(claim[0], Instant.parse(claim[1])));
    }

    @Override
    public List<Trigger> all() {
        List<Trigger> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(triggers)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                String name = f.getFileName().toString();
                String id = URLDecoder.decode(name.substring(0, name.length() - 5), StandardCharsets.UTF_8);
                get(id).ifPresent(out::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    @Override
    public synchronized void remove(String id) {
        try {
            Files.deleteIfExists(file(id));
            Files.deleteIfExists(claimFile(id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean claim(String id, String owner, Instant now, Duration staleAfter) {
        Optional<Trigger> t = get(id);
        if (t.isEmpty() || !t.get().enabled() || t.get().nextFire() == null || t.get().nextFire().isAfter(now)) return false;
        Path claim = claimFile(id);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (createClaim(claim, owner + "\n" + now)) return true;
            String[] c = readClaim(id);
            if (c == null || Instant.parse(c[1]).isAfter(now.minus(staleAfter))) return false; // held
            if (!takeOverStale(claim, now.minus(staleAfter))) return false;
        }
        return false;
    }

    /** Creates the claim file atomically with its content (a hard link fails if it exists). */
    private boolean createClaim(Path claim, String content) {
        try {
            Path tmp = Files.createTempFile(triggers, ".claim-", ".tmp");
            try {
                Files.writeString(tmp, content);
                Files.createLink(claim, tmp);
                return true;
            } catch (FileAlreadyExistsException held) {
                return false;
            } catch (UnsupportedOperationException noLinks) {
                try {
                    Files.writeString(claim, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    return true;
                } catch (FileAlreadyExistsException held) {
                    return false;
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Moves a dead process's claim aside. If what was moved turns out to be fresh (another process took
     * the claim over first), it is put back and the takeover fails.
     */
    boolean takeOverStale(Path claim, Instant staleBefore) {
        Path aside = claim.resolveSibling(claim.getFileName() + ".stale-" + java.util.UUID.randomUUID());
        try {
            Files.move(claim, aside, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchFileException gone) {
            return true; // released meanwhile: try to claim again
        } catch (IOException e) {
            return false;
        }
        try {
            String[] parts = Files.readString(aside).split("\n", 2);
            boolean stale = parts.length == 2 && Instant.parse(parts[1].trim()).isBefore(staleBefore);
            if (!stale) {
                try {
                    Files.createLink(claim, aside); // not ours to take: put it back
                } catch (IOException | UnsupportedOperationException ignored) {
                    // someone claimed again meanwhile; theirs stands
                }
            }
            return stale;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(aside);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    @Override
    public synchronized void complete(String id, String owner, Trigger next) {
        String[] c = readClaim(id);
        if (c == null || !c[0].equals(owner)) return; // rewritten (e.g. the run paused again): keep that
        if (next == null) {
            remove(id);
        } else {
            write(file(id), TriggerCodec.toJson(next.withClaim(null, null)));
            try {
                Files.deleteIfExists(claimFile(id));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public AutoCloseable tickLock() {
        try {
            FileChannel channel = FileChannel.open(dir.resolve("tick.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException sameProcess) {
                lock = null;
            }
            if (lock == null) {
                channel.close();
                return null;
            }
            FileLock held = lock;
            return () -> {
                held.release();
                channel.close();
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String[] readClaim(String id) {
        try {
            String[] parts = Files.readString(claimFile(id)).split("\n", 2);
            return parts.length == 2 ? new String[] {parts[0], parts[1].trim()} : null;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void write(Path target, String content) {
        try {
            Path tmp = Files.createTempFile(target.getParent(), ".write-", ".tmp");
            Files.writeString(tmp, content);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
