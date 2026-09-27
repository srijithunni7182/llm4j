package io.github.llm4j.getviral.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves an MP4's {@code moov} (index) box in front of the media data, like {@code qt-faststart}.
 * Browsers can then start playing before the whole file has downloaded, and Instagram's publishing
 * API requires it ("moov atom at the front of the file"). Chunk offsets in every {@code stco}/{@code co64}
 * table are shifted by the size of the moved box.
 */
public final class Mp4FastStart {

    private Mp4FastStart() { }

    record Box(String type, long offset, long size) { }

    /** Top-level boxes of an MP4 file. */
    static List<Box> boxes(FileChannel fc) throws IOException {
        List<Box> boxes = new ArrayList<>();
        long pos = 0, end = fc.size();
        ByteBuffer header = ByteBuffer.allocate(16);
        while (pos + 8 <= end) {
            header.clear().limit(8);
            fc.read(header, pos);
            header.flip();
            long size = Integer.toUnsignedLong(header.getInt());
            byte[] type = new byte[4];
            header.get(type);
            if (size == 1) {
                header.clear().limit(8);
                fc.read(header, pos + 8);
                header.flip();
                size = header.getLong();
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < 8 || pos + size > end) throw new IOException("Corrupt MP4 box at " + pos);
            boxes.add(new Box(new String(type, java.nio.charset.StandardCharsets.ISO_8859_1), pos, size));
            pos += size;
        }
        return boxes;
    }

    /** True if the file's index already comes before its media data. */
    public static boolean isFastStart(Path mp4) throws IOException {
        try (FileChannel fc = FileChannel.open(mp4, StandardOpenOption.READ)) {
            for (Box box : boxes(fc)) {
                if (box.type().equals("moov")) return true;
                if (box.type().equals("mdat")) return false;
            }
            return false;
        }
    }

    /** Rewrites {@code mp4} in place with the index first. Returns false if it already was. */
    public static boolean apply(Path mp4) throws IOException {
        Path tmp = mp4.resolveSibling(mp4.getFileName() + ".faststart");
        try (FileChannel in = FileChannel.open(mp4, StandardOpenOption.READ)) {
            List<Box> boxes = boxes(in);
            Box moov = boxes.stream().filter(b -> b.type().equals("moov")).findFirst()
                    .orElseThrow(() -> new IOException("No moov box"));
            Box mdat = boxes.stream().filter(b -> b.type().equals("mdat")).findFirst()
                    .orElseThrow(() -> new IOException("No mdat box"));
            if (moov.offset() < mdat.offset()) return false;
            if (moov.size() > 64L * 1024 * 1024) throw new IOException("moov box too large");

            ByteBuffer index = ByteBuffer.allocate((int) moov.size());
            in.read(index, moov.offset());
            index.flip();
            shiftChunkOffsets(index, 0, index.limit(), moov.size());

            try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                boolean written = false;
                for (Box box : boxes) {
                    if (box == moov) continue;
                    if (!written && box.offset() >= mdat.offset()) {
                        index.rewind();
                        while (index.hasRemaining()) out.write(index);
                        written = true;
                    }
                    long copied = 0;
                    while (copied < box.size()) {
                        copied += in.transferTo(box.offset() + copied, box.size() - copied, out);
                    }
                }
            }
        }
        Files.move(tmp, mp4, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    /**
     * Declares a Baseline H.264 track as <b>Constrained</b> Baseline (constraint_set0 and 1) in its
     * {@code avcC} record and SPS. jcodec's encoder only ever writes what that profile allows (one slice
     * group, slices in order, no redundant pictures) but leaves the flags at zero, and some hardware
     * decoders (Safari, Android, Windows) only accept Baseline streams that say they are constrained.
     * Patched in place; returns true if anything changed.
     */
    public static boolean markConstrainedBaseline(Path mp4) throws IOException {
        try (FileChannel fc = FileChannel.open(mp4, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            Box moov = boxes(fc).stream().filter(b -> b.type().equals("moov")).findFirst()
                    .orElseThrow(() -> new IOException("No moov box"));
            if (moov.size() > 64L * 1024 * 1024) throw new IOException("moov box too large");
            ByteBuffer index = ByteBuffer.allocate((int) moov.size());
            fc.read(index, moov.offset());
            byte[] b = index.array();
            boolean changed = false;
            for (int i = 0; i + 16 < b.length; i++) {
                if (b[i] != 'a' || b[i + 1] != 'v' || b[i + 2] != 'c' || b[i + 3] != 'C') continue;
                int body = i + 4;                                   // version, profile, compat, level, ...
                if ((b[body + 1] & 0xff) != 66) break;              // only Baseline
                int sps = body + 8;                                 // 6 header bytes + 2-byte SPS length
                if ((b[body + 5] & 0x1f) == 0 || (b[sps] & 0x1f) != 7) break;
                byte flags = (byte) 0xC0;
                if ((b[body + 2] & 0xC0) != 0xC0) { b[body + 2] |= flags; changed = true; }
                if ((b[sps + 2] & 0xC0) != 0xC0) { b[sps + 2] |= flags; changed = true; }
                break;
            }
            if (changed) fc.write(ByteBuffer.wrap(b), moov.offset());
            return changed;
        }
    }

    /** Walks container boxes inside {@code buf[from, to)} and adds {@code delta} to every chunk offset. */
    private static void shiftChunkOffsets(ByteBuffer buf, int from, int to, long delta) throws IOException {
        int pos = from;
        while (pos + 8 <= to) {
            long size = Integer.toUnsignedLong(buf.getInt(pos));
            String type = new String(new byte[] {buf.get(pos + 4), buf.get(pos + 5), buf.get(pos + 6), buf.get(pos + 7)},
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            int headerLen = 8;
            if (size == 1) {
                size = buf.getLong(pos + 8);
                headerLen = 16;
            }
            if (size < headerLen || pos + size > to) throw new IOException("Corrupt box inside moov: " + type);
            int body = pos + headerLen;
            switch (type) {
                case "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta" ->
                        shiftChunkOffsets(buf, body, (int) (pos + size), delta);
                case "stco" -> {
                    int count = buf.getInt(body + 4);
                    for (int i = 0; i < count; i++) {
                        int at = body + 8 + i * 4;
                        long shifted = Integer.toUnsignedLong(buf.getInt(at)) + delta;
                        if (shifted > 0xFFFFFFFFL) throw new IOException("Chunk offset overflow; needs co64");
                        buf.putInt(at, (int) shifted);
                    }
                }
                case "co64" -> {
                    int count = buf.getInt(body + 4);
                    for (int i = 0; i < count; i++) {
                        int at = body + 8 + i * 8;
                        buf.putLong(at, buf.getLong(at) + delta);
                    }
                }
                default -> { }
            }
            pos += (int) size;
        }
    }
}
