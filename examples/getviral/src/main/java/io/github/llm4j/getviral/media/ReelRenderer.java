package io.github.llm4j.getviral.media;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jcodec.api.awt.AWTSequenceEncoder;

/**
 * Renders the Reel's beat sheet into a real vertical MP4: each beat gets a background image with a
 * slow zoom-and-pan (Ken Burns), a punchy animated on-screen headline, the voiceover as subtitles,
 * story-style progress bars and the creator's handle. Encoded to H.264 in pure Java (jcodec).
 * The video is silent — the beat sheet names the soundtrack to add in your editor.
 */
public class ReelRenderer {

    public record Beat(String time, String shot, String voiceover, String onScreen) { }

    public record Result(Path file, double seconds, int frames) { }

    private static final Pattern RANGE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[-–]\\s*(\\d+(?:\\.\\d+)?)");

    private final int width;
    private final int height;
    private final int fps;

    public ReelRenderer(int width, int height, int fps) {
        this.width = width & ~1;
        this.height = height & ~1;
        this.fps = fps;
    }

    public Result render(List<Beat> beats, List<BufferedImage> backgrounds, String handle, double paceFactor,
                         Path output) throws IOException {
        if (beats.isEmpty()) throw new IllegalArgumentException("The Reel has no beats to render");
        List<BufferedImage> plates = new ArrayList<>();
        int plateW = (int) (width * 1.22), plateH = (int) (height * 1.22);
        for (int i = 0; i < beats.size(); i++) {
            if (!backgrounds.isEmpty()) {
                plates.add(PosterArt.cover(backgrounds.get(i % backgrounds.size()), plateW, plateH));
            } else {
                BufferedImage mood = new BufferedImage(plateW, plateH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = PosterArt.pen(mood);
                PosterArt.moodBackground(g, plateW, plateH, beats.get(i).shot() + i);
                g.dispose();
                plates.add(mood);
            }
        }
        double[] durations = beats.stream().mapToDouble(b -> seconds(b.time()) * paceFactor).toArray();
        double total = 0;
        for (double d : durations) total += d;

        AWTSequenceEncoder encoder = AWTSequenceEncoder.createSequenceEncoder(output.toFile(), fps);
        BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int frames = 0;
        try {
            for (int i = 0; i < beats.size(); i++) {
                int beatFrames = Math.max(1, (int) Math.round(durations[i] * fps));
                for (int f = 0; f < beatFrames; f++) {
                    drawFrame(frame, plates.get(i), beats, i, (double) f / beatFrames, (double) f / fps, handle);
                    encoder.encodeImage(frame);
                    frames++;
                }
            }
        } finally {
            encoder.finish();
        }
        return new Result(output, total, frames);
    }

    private void drawFrame(BufferedImage frame, BufferedImage plate, List<Beat> beats, int index, double progress,
                           double secondsIntoBeat, String handle) {
        Graphics2D g = PosterArt.pen(frame);
        Beat beat = beats.get(index);

        // Ken Burns: zoom 1.0 → 1.12 while drifting; direction alternates per beat.
        double zoom = 1.0 + 0.12 * ease(progress);
        double dir = index % 2 == 0 ? 1 : -1;
        double driftX = dir * (plate.getWidth() - width) * 0.35 * progress;
        double driftY = -(plate.getHeight() - height) * 0.2 * progress;
        double drawW = width * 1.22 * zoom, drawH = height * 1.22 * zoom;
        double x = (width - drawW) / 2 + driftX, y = (height - drawH) / 2 + driftY;
        g.drawImage(plate, (int) x, (int) y, (int) drawW, (int) drawH, null);
        PosterArt.legibilityScrim(g, width, height);

        // Flash on the cut for energy.
        if (secondsIntoBeat < 0.08 && index > 0) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setComposite(AlphaComposite.SrcOver);
        }

        // Headline pops in over the first quarter second.
        double pop = Math.min(1.0, secondsIntoBeat / 0.25);
        float scale = (float) (0.78 + 0.22 * easeOutBack(pop));
        PosterArt.headline(g, beat.onScreen(), (int) (width * 0.07), (int) (height * 0.42), (int) (width * 0.86),
                width * 0.13f, index % 2 == 0 ? -3 : 2, scale);

        subtitles(g, beat.voiceover());
        progressBars(g, beats.size(), index, progress);

        Font small = PosterArt.display(width / 30f);
        PosterArt.pill(g, "@" + handle, small, (int) (width * 0.05), (int) (height * 0.925),
                new Color(0, 0, 0, 120), Color.WHITE, 14, 7);
        g.setFont(PosterArt.display(width / 40f));
        g.setColor(new Color(255, 255, 255, 170));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("⚡ GetViral", width - fm.stringWidth("⚡ GetViral") - (int) (width * 0.05), (int) (height * 0.075));
        g.dispose();
    }

    private void subtitles(Graphics2D g, String voiceover) {
        if (voiceover == null || voiceover.isBlank()) return;
        Font font = PosterArt.body(width / 22f);
        List<String> lines = PosterArt.wrap(voiceover, font, g, (int) (width * 0.78));
        if (lines.size() > 3) lines = lines.subList(0, 3);
        FontMetrics fm = g.getFontMetrics(font);
        int lineH = fm.getHeight();
        int boxH = lineH * lines.size() + 24;
        int boxW = (int) (width * 0.86);
        int bx = (width - boxW) / 2, by = (int) (height * 0.76) - boxH / 2;
        g.setColor(new Color(0, 0, 0, 150));
        g.fill(new RoundRectangle2D.Float(bx, by, boxW, boxH, 28, 28));
        g.setFont(font);
        g.setColor(Color.WHITE);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            g.drawString(line, (width - fm.stringWidth(line)) / 2, by + 12 + fm.getAscent() + i * lineH);
        }
    }

    private void progressBars(Graphics2D g, int count, int index, double progress) {
        int margin = (int) (width * 0.04), gap = 6, top = (int) (height * 0.025);
        int barW = (width - margin * 2 - gap * (count - 1)) / count;
        for (int i = 0; i < count; i++) {
            int bx = margin + i * (barW + gap);
            g.setColor(new Color(255, 255, 255, 80));
            g.fillRoundRect(bx, top, barW, 4, 4, 4);
            double fill = i < index ? 1 : i == index ? progress : 0;
            g.setColor(Color.WHITE);
            g.fillRoundRect(bx, top, (int) (barW * fill), 4, 4, 4);
        }
    }

    /** "0-3s" → 3.0; unknown formats → 3s; clamped to 1.5–6s per beat. */
    static double seconds(String time) {
        if (time != null) {
            Matcher m = RANGE.matcher(time);
            if (m.find()) {
                double d = Double.parseDouble(m.group(2)) - Double.parseDouble(m.group(1));
                if (d > 0) return Math.max(1.5, Math.min(6, d));
            }
        }
        return 3.0;
    }

    private static double ease(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double easeOutBack(double t) {
        double c1 = 1.70158, c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }
}
