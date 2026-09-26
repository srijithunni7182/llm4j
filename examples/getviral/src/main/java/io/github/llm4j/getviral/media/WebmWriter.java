package io.github.llm4j.getviral.media;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.jcodec.codecs.vpx.NopRateControl;
import org.jcodec.codecs.vpx.VP8Encoder;
import org.jcodec.common.Codec;
import org.jcodec.common.MuxerTrack;
import org.jcodec.common.VideoCodecMeta;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Size;
import org.jcodec.containers.mkv.muxer.MKVMuxer;
import org.jcodec.scale.AWTUtil;

/**
 * A WebM (VP8) copy of the Reel for browsers that can't decode H.264 — some Linux builds of Chromium
 * and Firefox. The MP4 stays the master (it is what Instagram and YouTube take); this is only for
 * playing the Reel in the studio. Pure Java (jcodec), every frame a keyframe, so it is rendered
 * smaller than the master to keep the file light.
 */
public class WebmWriter implements AutoCloseable {

    private final int width;
    private final int height;
    private final int fps;
    private final Path file;
    private final SeekableByteChannel channel;
    private final MKVMuxer muxer;
    private final MuxerTrack track;
    private final VP8Encoder encoder;
    private final BufferedImage scaled;
    private final ByteBuffer buffer;
    private int frames;
    private boolean finished;

    public WebmWriter(Path file, int width, int height, int fps, int quantizer) throws IOException {
        this.width = width & ~1;
        this.height = height & ~1;
        this.fps = fps;
        this.file = file;
        this.channel = NIOUtils.writableChannel(file.toFile());
        this.muxer = new MKVMuxer(channel);
        this.track = muxer.addVideoTrack(Codec.VP8,
                VideoCodecMeta.createSimpleVideoCodecMeta(new Size(this.width, this.height), ColorSpace.YUV420J));
        this.encoder = new VP8Encoder(new NopRateControl(quantizer));
        this.scaled = new BufferedImage(this.width, this.height, BufferedImage.TYPE_INT_RGB);
        this.buffer = ByteBuffer.allocate(this.width * this.height * 3);
    }

    /** Adds one frame (scaled to this writer's size). */
    public void encode(BufferedImage frame) throws IOException {
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(frame, 0, 0, width, height, null);
        g.dispose();
        Picture picture = AWTUtil.fromBufferedImage(scaled, ColorSpace.YUV420J);
        buffer.clear();
        ByteBuffer out = encoder.encodeFrame(picture, buffer).getData();
        ByteBuffer copy = ByteBuffer.allocate(out.remaining());
        copy.put(out).flip();
        // jcodec's Matroska muxer stores (pts - 1) in its own timecode units; header() makes one unit one frame.
        track.addFrame(Packet.createPacket(copy, frames + 1, fps, 1, frames, Packet.FrameType.KEY, null));
        frames++;
    }

    public int frames() {
        return frames;
    }

    @Override
    public void close() throws IOException {
        if (finished) return;
        finished = true;
        try {
            muxer.finish();
        } finally {
            channel.close();
        }
        fixHeader(file, fps, frames);
    }

    /**
     * jcodec writes a fixed 40 ms TimecodeScale (25 fps) and a wrong Duration. Rewrites both in place so
     * one timecode unit is exactly one frame and the duration is the real length (same byte lengths).
     */
    static void fixHeader(Path file, int fps, int frames) throws IOException {
        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer head = ByteBuffer.allocate((int) Math.min(fc.size(), 8192));
            fc.read(head, 0);
            byte[] b = head.array();
            int scaleAt = -1, scaleLen = 0, durationAt = -1, durationLen = 0;
            for (int i = 0; i + 4 < head.position(); i++) {
                if (scaleAt < 0 && (b[i] & 0xff) == 0x2A && (b[i + 1] & 0xff) == 0xD7 && (b[i + 2] & 0xff) == 0xB1) {
                    scaleLen = b[i + 3] & 0x7f;
                    scaleAt = i + 4;
                }
                if (durationAt < 0 && (b[i] & 0xff) == 0x44 && (b[i + 1] & 0xff) == 0x89) {
                    durationLen = b[i + 2] & 0x7f;
                    durationAt = i + 3;
                }
            }
            if (scaleAt < 0 || durationAt < 0 || (durationLen != 4 && durationLen != 8) || scaleLen < 4) {
                throw new IOException("Unexpected WebM header layout");
            }
            long scale = Math.round(1e9 / fps);
            ByteBuffer s = ByteBuffer.allocate(scaleLen);
            for (int k = scaleLen - 1; k >= 0; k--) s.put((byte) (scale >>> (8 * k)));
            fc.write(s.flip(), scaleAt);
            ByteBuffer d = ByteBuffer.allocate(durationLen);
            if (durationLen == 8) d.putDouble(frames); else d.putFloat(frames);
            fc.write(d.flip(), durationAt);
        }
    }
}
