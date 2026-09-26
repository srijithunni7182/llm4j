package io.github.llm4j.getviral.media;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.getviral.media.MediaInspector.Status;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.jcodec.api.awt.AWTSequenceEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaInspectorTest {

    @TempDir
    Path dir;

    private static List<ReelRenderer.Beat> beats() {
        return List.of(new ReelRenderer.Beat("0-2s", "close-up", "Hook line", "HOOK"),
                new ReelRenderer.Beat("2-4s", "b-roll", "Payoff line", "PAYOFF"));
    }

    @Test
    void renderedReelIsFastStartDecodesAndHasAMatchingWebmCopy() throws Exception {
        Path mp4 = dir.resolve("reel.mp4");
        Path webm = dir.resolve("reel-preview.webm");
        ReelRenderer.Result result = new ReelRenderer(180, 320, 24).render(beats(), List.of(), "tester", 1.0, mp4, webm);

        assertThat(Mp4FastStart.isFastStart(mp4)).isTrue();
        assertThat(MediaInspector.reel(mp4)).extracting(MediaInspector.Check::status).containsOnly(Status.PASS);
        assertThat(MediaInspector.webm(webm, result.seconds())).extracting(MediaInspector.Check::status).containsOnly(Status.PASS);
    }

    @Test
    void fastStartMovesTheIndexAndKeepsTheVideoDecodable() throws Exception {
        // jcodec's own encoder writes the index at the end, which Instagram rejects.
        Path mp4 = dir.resolve("plain.mp4");
        AWTSequenceEncoder encoder = AWTSequenceEncoder.createSequenceEncoder(mp4.toFile(), 24);
        BufferedImage frame = new BufferedImage(180, 320, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 24 * 4; i++) {
            Graphics2D g = frame.createGraphics();
            g.setColor(new Color(20, 20, 40));
            g.fillRect(0, 0, 180, 320);
            g.setColor(Color.ORANGE);
            g.fillOval(10 + i, 100, 60, 60);
            g.dispose();
            encoder.encodeImage(frame);
        }
        encoder.finish();
        assertThat(Mp4FastStart.isFastStart(mp4)).isFalse();
        assertThat(MediaInspector.reel(mp4)).anySatisfy(c -> assertThat(c.status()).isEqualTo(Status.FAIL));

        assertThat(Mp4FastStart.apply(mp4)).isTrue();
        assertThat(Mp4FastStart.isFastStart(mp4)).isTrue();
        assertThat(MediaInspector.reel(mp4)).extracting(MediaInspector.Check::status).containsOnly(Status.PASS);
        assertThat(Mp4FastStart.apply(mp4)).as("idempotent").isFalse();
    }

    @Test
    void truncatedReelIsCaughtBeforeAnyonePostsIt() throws Exception {
        Path mp4 = dir.resolve("reel.mp4");
        new ReelRenderer(180, 320, 24).render(beats(), List.of(), "tester", 1.0, mp4, null);
        byte[] bytes = Files.readAllBytes(mp4);
        Files.write(mp4, java.util.Arrays.copyOf(bytes, bytes.length / 2));
        assertThat(MediaInspector.reel(mp4)).anySatisfy(c -> assertThat(c.status()).isEqualTo(Status.FAIL));
    }

    @Test
    void blankOrWronglyShapedImagesFail() throws Exception {
        Path blank = dir.resolve("cover.png");
        ImageIO.write(new BufferedImage(90, 160, BufferedImage.TYPE_INT_RGB), "png", blank.toFile());
        MediaAsset cover = new MediaAsset("image", "reel_cover", "/m/cover.png", blank, "t", false, "", 90, 160, 0, null);
        assertThat(MediaInspector.image(cover)).anySatisfy(c -> assertThat(c.detail()).contains("blank"));

        MediaAsset thumb = new MediaAsset("image", "youtube_thumbnail", "/m/cover.png", blank, "t", false, "", 90, 160, 0, null);
        assertThat(MediaInspector.image(thumb)).anySatisfy(c -> assertThat(c.detail()).contains("expected 16:9"));
    }
}
