package io.github.llm4j.loom.inspect.emit;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * The durable local log every observed event is written to before anything is sent. Append-only segment files of length-prefixed,
 * checksummed records, each with an increasing offset. A record cut off by a crash is found by its length and checksum and dropped
 * when the log is opened again; everything before it is intact.
 *
 * <p>Record: {@code int length, long offset, int crc32(offset + payload), payload}. A segment is named for the offset of its first
 * record, so what a deleted segment held is known without reading it. The oldest segments go first when the log passes its size or
 * age cap, and the loss shows as {@link #firstOffset()} moving past what a reader had acknowledged.
 *
 * <p>Thread-safe. Writes are buffered: call {@link #flush()} about every 200 ms and {@link #sync()} about every second (the owner
 * does), which is the stated bound on what a crash or a power cut can lose.
 */
public final class Outbox implements AutoCloseable {

    /** One stored record. */
    public record Entry(long offset, byte[] payload) { }

    private static final int HEADER = 4 + 8 + 4;
    private static final int MAX_RECORD = 16 * 1024 * 1024;

    private final Path dir;
    private final long segmentBytes;
    private final long maxBytes;
    private final Duration maxAge;
    private final Clock clock;
    private final FileChannel lockChannel;
    private final FileLock lock;

    private FileChannel active;
    private OutputStream out;
    private long activeFirst;
    private long activeBytes;
    private long next;
    private boolean closed;

    /** Opens (creating it if needed) the log in {@code dir}, repairing a torn tail, and takes the directory's lock. */
    public Outbox(Path dir, long segmentBytes, long maxBytes, Duration maxAge, Clock clock) {
        this.dir = dir;
        this.segmentBytes = segmentBytes;
        this.maxBytes = maxBytes;
        this.maxAge = maxAge;
        this.clock = clock;
        try {
            makePrivateDir(dir);
            lockChannel = FileChannel.open(dir.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock got = null;
            try {
                got = lockChannel.tryLock();
            } catch (OverlappingFileLockException e) {
                // held by this JVM already
            }
            if (got == null) {
                lockChannel.close();
                throw new IllegalStateException("the outbox " + dir + " is in use by another process");
            }
            lock = got;
            recover();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void makePrivateDir(Path dir) throws IOException {
        if (Files.exists(dir)) return;
        try {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(dir);
        }
    }

    // ------------------------------------------------------------------------------ writing

    /** Appends a record and returns its offset. */
    public synchronized long append(byte[] payload) {
        if (closed) throw new IllegalStateException("closed");
        if (payload.length > MAX_RECORD) throw new IllegalArgumentException("record too large: " + payload.length);
        try {
            if (out == null || (activeBytes >= segmentBytes)) roll();
            long offset = next++;
            ByteBuffer head = ByteBuffer.allocate(HEADER);
            head.putInt(payload.length).putLong(offset).putInt(crc(offset, payload));
            out.write(head.array());
            out.write(payload);
            activeBytes += HEADER + payload.length;
            return offset;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Pushes buffered records to the file (visible to readers; survives a process crash, not a power cut). */
    public synchronized void flush() {
        try {
            if (out != null) out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Flushes and forces the file to disk (survives a power cut). */
    public synchronized void sync() {
        try {
            if (out != null) {
                out.flush();
                active.force(false);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void roll() throws IOException {
        if (out != null) {
            out.flush();
            active.force(false);
            out.close();
        }
        activeFirst = next;
        Path file = segmentFile(activeFirst);
        active = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system
        }
        out = new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(active), 64 * 1024);
        activeBytes = 0;
        enforceCaps();
    }

    private Path segmentFile(long first) {
        return dir.resolve(String.format("seg-%012d.log", first));
    }

    /** Drops the oldest segments while the log is over its size or age cap. The active segment is never dropped. */
    private void enforceCaps() throws IOException {
        List<Long> segs = segments();
        long total = 0;
        for (long s : segs) total += Files.size(segmentFile(s));
        long now = clock.millis();
        for (int i = 0; i < segs.size() - 1; i++) {
            long first = segs.get(i);
            Path f = segmentFile(first);
            long age = now - Files.getLastModifiedTime(f).toMillis();
            if (total > maxBytes || age > maxAge.toMillis()) {
                total -= Files.size(f);
                Files.delete(f);
            } else {
                break;
            }
        }
    }

    /** Deletes whole segments that every reader has passed: those whose last offset is at or below {@code acknowledged}. */
    public synchronized void trim(long acknowledged) {
        try {
            List<Long> segs = segments();
            for (int i = 0; i + 1 < segs.size(); i++) {
                if (segs.get(i + 1) - 1 <= acknowledged) Files.deleteIfExists(segmentFile(segs.get(i)));
                else break;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------------------ reading

    /** The offset the next record will get. */
    public synchronized long nextOffset() {
        return next;
    }

    /** The offset of the oldest record still held (the next offset when the log is empty). */
    public synchronized long firstOffset() {
        try {
            List<Long> segs = segments();
            return segs.isEmpty() ? next : segs.get(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Up to {@code max} records with an offset greater than {@code after}, oldest first, flushing first so nothing written is missed. */
    public synchronized List<Entry> read(long after, int max) {
        flush();
        List<Entry> found = new ArrayList<>();
        try {
            List<Long> segs = segments();
            for (int i = 0; i < segs.size() && found.size() < max; i++) {
                long first = segs.get(i);
                long nextFirst = i + 1 < segs.size() ? segs.get(i + 1) : Long.MAX_VALUE;
                if (nextFirst - 1 <= after) continue;
                try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(segmentFile(first)))) {
                    DataInputStream data = new DataInputStream(in);
                    while (found.size() < max) {
                        Entry e = readOne(data);
                        if (e == null) break;
                        if (e.offset() > after) found.add(e);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static Entry readOne(DataInputStream in) throws IOException {
        int len;
        try {
            len = in.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (len < 0 || len > MAX_RECORD) return null;
        long offset;
        int crc;
        byte[] payload = new byte[len];
        try {
            offset = in.readLong();
            crc = in.readInt();
            in.readFully(payload);
        } catch (EOFException e) {
            return null;
        }
        return crc == crc(offset, payload) ? new Entry(offset, payload) : null;
    }

    private static int crc(long offset, byte[] payload) {
        CRC32 c = new CRC32();
        c.update(ByteBuffer.allocate(8).putLong(offset).array());
        c.update(payload);
        return (int) c.getValue();
    }

    // ------------------------------------------------------------------------------ opening

    private List<Long> segments() throws IOException {
        List<Long> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("seg-") && n.endsWith(".log"))
                    .forEach(n -> out.add(Long.parseLong(n.substring(4, n.length() - 4))));
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** Finds where the log ends, cutting off a record that a crash left half written. */
    private void recover() throws IOException {
        List<Long> segs = segments();
        if (segs.isEmpty()) {
            next = 1;
            return;
        }
        long last = segs.get(segs.size() - 1);
        Path file = segmentFile(last);
        long good = 0;
        long lastOffset = last - 1;
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file))) {
            DataInputStream data = new DataInputStream(in);
            Entry e;
            while ((e = readOne(data)) != null) {
                good += HEADER + e.payload().length;
                lastOffset = e.offset();
            }
        }
        if (good < Files.size(file)) {
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ch.truncate(good);
            }
        }
        next = lastOffset + 1;
        // the next record starts a new segment, so a reopened log never appends into a file another version wrote
    }

    // ------------------------------------------------------------------------------ misc

    /** The directory this log lives in. */
    public Path dir() {
        return dir;
    }

    /** True when a live process holds the lock of the outbox in {@code dir}. */
    public static boolean inUse(Path dir) {
        Path lockFile = dir.resolve("lock");
        if (!Files.exists(lockFile)) return false;
        try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
            FileLock l = ch.tryLock();
            if (l == null) return true;
            l.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            return true; // when unsure, leave it alone
        }
    }

    /** Writes a small file atomically (a cursor): the new content replaces the old whole or not at all. */
    static void writeAtomically(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (out != null) {
                out.flush();
                active.force(false);
                out.close();
            }
            lock.release();
            lockChannel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
