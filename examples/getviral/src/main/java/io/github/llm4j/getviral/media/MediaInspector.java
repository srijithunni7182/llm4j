package io.github.llm4j.getviral.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;

/**
 * Deterministic checks on generated files: does each one actually open, decode and meet the target
 * platform's spec? The Reel is checked against Instagram's Reels publishing spec (H.264, 23–60 fps,
 * 9:16, index at the front, no edit lists, 3 s – 15 min) and decoded frame by frame at the start,
 * middle and end, so a file that "exists" but won't play is caught before a creator posts it.
 */
public final class MediaInspector {

    public enum Status { PASS, WARN, FAIL }

    public record Check(String artifact, Status status, String detail) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("artifact", artifact);
            m.put("status", status.name());
            m.put("detail", detail);
            return m;
        }
    }

    private MediaInspector() { }

    // ── images ───────────────────────────────────────────────────────────────────────────────

    /** Expected aspect ratio (w/h) per image purpose, or 0 if any. */
    static double expectedAspect(String purpose) {
        if (purpose.startsWith("youtube_thumbnail") || purpose.startsWith("x_card")) return 16.0 / 9;
        if (purpose.startsWith("reel_cover") || purpose.startsWith("broll")) return 9.0 / 16;
        return 0;
    }

    public static List<Check> image(MediaAsset asset) {
        List<Check> checks = new ArrayList<>();
        String name = asset.purpose();
        BufferedImage image;
        try {
            image = ImageIO.read(asset.file().toFile());
        } catch (IOException e) {
            image = null;
        }
        if (image == null) {
            checks.add(new Check(name, Status.FAIL, "file doesn't decode as an image"));
            return checks;
        }
        double aspect = expectedAspect(name);
        double actual = (double) image.getWidth() / image.getHeight();
        if (aspect > 0 && Math.abs(actual - aspect) / aspect > 0.02) {
            checks.add(new Check(name, Status.FAIL, String.format("aspect %.3f, expected %s", actual,
                    aspect > 1 ? "16:9" : "9:16")));
        }
        double spread = lumaSpread(image);
        if (spread < 4) {
            checks.add(new Check(name, Status.FAIL, "image is blank (a single flat colour)"));
        }
        if (name.startsWith("youtube_thumbnail") && image.getWidth() < 1280) {
            checks.add(new Check(name, Status.WARN, image.getWidth() + "x" + image.getHeight()
                    + " — YouTube recommends 1280x720 or larger"));
        }
        if (checks.isEmpty()) {
            checks.add(new Check(name, Status.PASS, image.getWidth() + "x" + image.getHeight() + ", decodes, not blank"));
        }
        return checks;
    }

    /** Standard deviation of luminance over a sparse grid — ~0 means a flat, empty picture. */
    static double lumaSpread(BufferedImage image) {
        int stepX = Math.max(1, image.getWidth() / 48), stepY = Math.max(1, image.getHeight() / 48);
        double sum = 0, sq = 0;
        int n = 0;
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                int rgb = image.getRGB(x, y);
                double l = 0.299 * ((rgb >> 16) & 0xff) + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff);
                sum += l;
                sq += l * l;
                n++;
            }
        }
        double mean = sum / n;
        return Math.sqrt(Math.max(0, sq / n - mean * mean));
    }

    // ── the Reel (MP4 master) ────────────────────────────────────────────────────────────────

    public static List<Check> reel(Path mp4) {
        List<Check> checks = new ArrayList<>();
        String name = "reel.mp4";
        try {
            long size = Files.size(mp4);
            if (size < 1024) {
                checks.add(new Check(name, Status.FAIL, "file is empty or truncated (" + size + " bytes)"));
                return checks;
            }
            byte[] moov = moovBytes(mp4);
            if (moov == null) {
                checks.add(new Check(name, Status.FAIL, "no moov index — the file is unfinished and won't play"));
                return checks;
            }
            String index = new String(moov, StandardCharsets.ISO_8859_1);
            checks.add(Mp4FastStart.isFastStart(mp4)
                    ? new Check(name, Status.PASS, "index (moov) at the front — streams in browsers, accepted by Instagram")
                    : new Check(name, Status.FAIL, "index (moov) is at the end — Instagram rejects this; browsers must download it all first"));
            if (index.contains("elst")) {
                checks.add(new Check(name, Status.WARN, "contains an edit list — Instagram's spec asks for none"));
            }
            if (!index.contains("avc1")) {
                checks.add(new Check(name, Status.FAIL, "video isn't H.264 (avc1) — Instagram and most browsers need H.264"));
            }

            try (SeekableByteChannel ch = NIOUtils.readableChannel(mp4.toFile())) {
                FrameGrab grab = FrameGrab.createFrameGrab(ch);
                DemuxerTrackMeta meta = grab.getVideoTrack().getMeta();
                double seconds = meta.getTotalDuration();
                int frames = meta.getTotalFrames();
                double fps = seconds > 0 ? frames / seconds : 0;
                var size2 = meta.getVideoCodecMeta().getSize();
                int w = size2.getWidth(), h = size2.getHeight();
                if (Math.abs((double) w / h - 9.0 / 16) > 0.01) {
                    checks.add(new Check(name, Status.FAIL, w + "x" + h + " isn't 9:16 vertical"));
                }
                if (fps < 23 || fps > 60) {
                    checks.add(new Check(name, Status.FAIL, String.format("%.1f fps — Instagram needs 23–60 fps", fps)));
                }
                if (seconds < 3 || seconds > 900) {
                    checks.add(new Check(name, Status.FAIL, String.format("%.1fs — Reels must be 3 s to 15 min", seconds)));
                }
                // Decode real frames: start, middle, end. A file that only *looks* right fails here.
                int[] probes = {0, frames / 2, Math.max(0, frames - 1)};
                double minSpread = Double.MAX_VALUE;
                for (int probe : probes) {
                    Picture picture = grab.seekToFramePrecise(probe).getNativeFrame();
                    if (picture == null) {
                        checks.add(new Check(name, Status.FAIL, "frame " + probe + " of " + frames + " doesn't decode"));
                        return checks;
                    }
                    minSpread = Math.min(minSpread, lumaSpread(AWTUtil.toBufferedImage(picture)));
                }
                if (minSpread < 4) {
                    checks.add(new Check(name, Status.WARN, "a sampled frame is a flat colour — check the Reel isn't blank"));
                }
                checks.add(new Check(name, Status.PASS, String.format("H.264 %dx%d, %.1fs, %d frames at %.0f fps; "
                        + "start, middle and end frames decode", w, h, seconds, frames, fps)));
            }
        } catch (Exception e) {
            checks.add(new Check(name, Status.FAIL, "doesn't decode: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " — " + e.getMessage())));
        }
        return checks;
    }

    private static byte[] moovBytes(Path mp4) throws IOException {
        try (FileChannel fc = FileChannel.open(mp4, StandardOpenOption.READ)) {
            for (Mp4FastStart.Box box : Mp4FastStart.boxes(fc)) {
                if (box.type().equals("moov") && box.size() < 64L * 1024 * 1024) {
                    ByteBuffer buf = ByteBuffer.allocate((int) box.size());
                    fc.read(buf, box.offset());
                    return buf.array();
                }
            }
        }
        return null;
    }

    // ── the WebM browser copy ────────────────────────────────────────────────────────────────

    public static List<Check> webm(Path webm, double expectedSeconds) {
        List<Check> checks = new ArrayList<>();
        String name = "reel-preview.webm";
        try (FileChannel fc = FileChannel.open(webm, StandardOpenOption.READ)) {
            ByteBuffer head = ByteBuffer.allocate((int) Math.min(fc.size(), 8192));
            fc.read(head, 0);
            byte[] b = head.array();
            if (head.position() < 64 || (b[0] & 0xff) != 0x1A || (b[1] & 0xff) != 0x45 || (b[2] & 0xff) != 0xDF
                    || (b[3] & 0xff) != 0xA3) {
                checks.add(new Check(name, Status.FAIL, "not a WebM file"));
                return checks;
            }
            String text = new String(b, 0, head.position(), StandardCharsets.ISO_8859_1);
            if (!text.contains("webm")) checks.add(new Check(name, Status.FAIL, "doctype isn't webm — browsers won't play it"));
            if (!text.contains("V_VP8")) checks.add(new Check(name, Status.FAIL, "video isn't VP8"));
            double seconds = headerSeconds(b, head.position());
            if (seconds <= 0 || Math.abs(seconds - expectedSeconds) > 0.6) {
                checks.add(new Check(name, Status.FAIL, String.format("header says %.1fs but the Reel is %.1fs", seconds, expectedSeconds)));
            }
            if (checks.isEmpty()) {
                checks.add(new Check(name, Status.PASS, String.format("VP8 WebM, %.1fs — plays in browsers without H.264", seconds)));
            }
        } catch (IOException e) {
            checks.add(new Check(name, Status.FAIL, "can't read: " + e.getMessage()));
        }
        return checks;
    }

    /** Duration from the Matroska header: Duration (0x4489) × TimecodeScale (0x2AD7B1) ns. */
    static double headerSeconds(byte[] b, int len) {
        long scale = 1_000_000;
        double duration = -1;
        for (int i = 0; i + 4 < len; i++) {
            if ((b[i] & 0xff) == 0x2A && (b[i + 1] & 0xff) == 0xD7 && (b[i + 2] & 0xff) == 0xB1) {
                int n = b[i + 3] & 0x7f;
                long v = 0;
                for (int k = 0; k < n && i + 4 + k < len; k++) v = (v << 8) | (b[i + 4 + k] & 0xff);
                scale = v;
            }
            if (duration < 0 && (b[i] & 0xff) == 0x44 && (b[i + 1] & 0xff) == 0x89) {
                int n = b[i + 2] & 0x7f;
                ByteBuffer v = ByteBuffer.wrap(b, i + 3, n);
                duration = n == 8 ? v.getDouble() : n == 4 ? v.getFloat() : -1;
            }
        }
        return duration < 0 ? -1 : duration * scale / 1e9;
    }
}
