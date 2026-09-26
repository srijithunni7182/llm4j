package io.github.llm4j.getviral.media;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * GetViral's Java2D design kit: the studio's sunset palette, gradient "mood" backgrounds, and bold
 * creator-style typography. Used for the offline image fallback, for text overlays on AI images,
 * and for every frame of the rendered Reel.
 */
public final class PosterArt {

    public static final Color HOT = new Color(0xFF2E88);
    public static final Color AMBER = new Color(0xFF9A3D);
    public static final Color VIOLET = new Color(0x8B5CFF);
    public static final Color CYAN = new Color(0x2AD4F2);
    public static final Color INK = new Color(0x07060B);

    private static final Color[][] PALETTES = {
        {new Color(0xFF2E88), new Color(0xFF9A3D), new Color(0x2B0F3A)},
        {new Color(0x8B5CFF), new Color(0x2AD4F2), new Color(0x0B1030)},
        {new Color(0xFF9A3D), new Color(0xFFD166), new Color(0x3A0F1F)},
        {new Color(0x2AD4F2), new Color(0xB9F36C), new Color(0x082A2A)},
        {new Color(0xFF2E88), new Color(0x8B5CFF), new Color(0x120A2A)},
    };

    private PosterArt() { }

    public static Graphics2D pen(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    /** A rich gradient "mood" background whose palette and composition are seeded by {@code seed}. */
    public static void moodBackground(Graphics2D g, int w, int h, String seed) {
        Random random = new Random(seed == null ? 7 : seed.hashCode());
        Color[] p = PALETTES[Math.floorMod(seed == null ? 0 : seed.hashCode(), PALETTES.length)];
        g.setPaint(new GradientPaint(0, 0, p[2], w, h, INK));
        g.fillRect(0, 0, w, h);
        for (int i = 0; i < 4; i++) {
            float cx = w * (0.1f + random.nextFloat() * 0.8f);
            float cy = h * (0.1f + random.nextFloat() * 0.8f);
            float r = Math.max(w, h) * (0.35f + random.nextFloat() * 0.35f);
            Color c = p[i % 2];
            g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), r, new float[] {0f, 1f},
                    new Color[] {new Color(c.getRed(), c.getGreen(), c.getBlue(), 190), new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
            g.fillRect(0, 0, w, h);
        }
        // bokeh: out-of-focus light discs, like a shallow-depth-of-field photo
        int discs = 14 + random.nextInt(10);
        for (int i = 0; i < discs; i++) {
            float r = Math.min(w, h) * (0.02f + random.nextFloat() * 0.09f);
            float cx = random.nextFloat() * w, cy = random.nextFloat() * h;
            Color c = random.nextInt(3) == 0 ? Color.WHITE : p[random.nextInt(2)];
            int alpha = 40 + random.nextInt(70);
            g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), r, new float[] {0f, 0.72f, 1f},
                    new Color[] {new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha),
                            new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (alpha * 0.8)),
                            new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
            g.fill(new java.awt.geom.Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        }
        // soft diagonal light streak
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.12f));
        g.setPaint(new GradientPaint(0, 0, Color.WHITE, w * 0.6f, h * 0.6f, new Color(255, 255, 255, 0)));
        g.fillRect(0, 0, w, h);
        g.setComposite(AlphaComposite.SrcOver);
        grain(g, w, h, random);
    }

    private static void grain(Graphics2D g, int w, int h, Random random) {
        int dots = (w * h) / 90;
        for (int i = 0; i < dots; i++) {
            int a = 10 + random.nextInt(22);
            g.setColor(random.nextBoolean() ? new Color(255, 255, 255, a) : new Color(0, 0, 0, a));
            g.fillRect(random.nextInt(w), random.nextInt(h), 1, 1);
        }
    }

    /** Darkens top and bottom so white type stays legible on any image. */
    public static void legibilityScrim(Graphics2D g, int w, int h) {
        g.setPaint(new GradientPaint(0, h * 0.45f, new Color(0, 0, 0, 0), 0, h, new Color(0, 0, 0, 185)));
        g.fillRect(0, (int) (h * 0.45f), w, h);
        g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 120), 0, h * 0.22f, new Color(0, 0, 0, 0)));
        g.fillRect(0, 0, w, (int) (h * 0.22f));
    }

    public static Font display(float size) {
        Font font = new Font("DejaVu Sans", Font.BOLD, 1);
        if (!font.getFamily().startsWith("DejaVu")) font = new Font(Font.SANS_SERIF, Font.BOLD, 1);
        return font.deriveFont(size);
    }

    public static Font body(float size) {
        Font font = new Font("DejaVu Sans", Font.PLAIN, 1);
        if (!font.getFamily().startsWith("DejaVu")) font = new Font(Font.SANS_SERIF, Font.PLAIN, 1);
        return font.deriveFont(size);
    }

    /** Word-wraps {@code text} to {@code maxWidth}. */
    public static List<String> wrap(String text, Font font, Graphics2D g, int maxWidth) {
        FontMetrics fm = g.getFontMetrics(font);
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(candidate) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    /**
     * Big, stroked, slightly tilted creator-style headline, auto-sized to fit the box.
     * {@code anchorY} is the vertical centre of the text block.
     */
    public static void headline(Graphics2D g, String text, int x, int anchorY, int maxWidth, float maxSize,
                                double tiltDegrees, float scale) {
        if (text == null || text.isBlank()) return;
        String upper = text.toUpperCase();
        float size = maxSize;
        List<String> lines;
        do {
            lines = wrap(upper, display(size), g, maxWidth);
            size -= 4;
        } while ((lines.size() > 4 || widest(lines, display(size + 4), g) > maxWidth) && size > 18);
        Font font = display(size + 4);
        FontRenderContext frc = g.getFontRenderContext();
        float lineHeight = font.getSize2D() * 1.05f;
        float top = anchorY - (lines.size() * lineHeight) / 2f + font.getSize2D() * 0.8f;
        AffineTransform saved = g.getTransform();
        g.rotate(Math.toRadians(tiltDegrees), x + maxWidth / 2.0, anchorY);
        g.translate(x + maxWidth / 2.0, anchorY);
        g.scale(scale, scale);
        g.translate(-(x + maxWidth / 2.0), -anchorY);
        for (int i = 0; i < lines.size(); i++) {
            TextLayout layout = new TextLayout(lines.get(i), font, frc);
            float lx = x + (maxWidth - (float) layout.getBounds().getWidth()) / 2f;
            float ly = top + i * lineHeight;
            Shape outline = layout.getOutline(AffineTransform.getTranslateInstance(lx, ly));
            g.setColor(new Color(0, 0, 0, 120));
            g.fill(AffineTransform.getTranslateInstance(0, font.getSize2D() * 0.06).createTransformedShape(outline));
            g.setStroke(new BasicStroke(Math.max(2f, font.getSize2D() / 14f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(10, 6, 20));
            g.draw(outline);
            g.setColor(i == lines.size() - 1 && lines.size() > 1 ? new Color(0xFFD166) : Color.WHITE);
            g.fill(outline);
        }
        g.setTransform(saved);
    }

    private static int widest(List<String> lines, Font font, Graphics2D g) {
        FontMetrics fm = g.getFontMetrics(font);
        return lines.stream().mapToInt(fm::stringWidth).max().orElse(0);
    }

    /** A rounded "pill" label (e.g. brand watermark or subtitle box). */
    public static void pill(Graphics2D g, String text, Font font, int x, int y, Color fill, Color ink, int padX, int padY) {
        FontMetrics fm = g.getFontMetrics(font);
        int w = fm.stringWidth(text) + padX * 2;
        int h = fm.getAscent() + fm.getDescent() + padY * 2;
        g.setColor(fill);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, h, h));
        g.setColor(ink);
        g.setFont(font);
        g.drawString(text, x + padX, y + padY + fm.getAscent());
    }

    /** Scales {@code source} to cover {@code w×h} (centre crop), like CSS object-fit: cover. */
    public static BufferedImage cover(BufferedImage source, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = pen(out);
        double scale = Math.max((double) w / source.getWidth(), (double) h / source.getHeight());
        int sw = (int) Math.ceil(source.getWidth() * scale);
        int sh = (int) Math.ceil(source.getHeight() * scale);
        g.drawImage(source, (w - sw) / 2, (h - sh) / 2, sw, sh, null);
        g.dispose();
        return out;
    }

    /** Labels a local (non-AI) render with its subject so the placeholder still reads as intentional. */
    public static BufferedImage withCaption(BufferedImage base, String prompt) {
        BufferedImage out = cover(base, base.getWidth(), base.getHeight());
        String subject = prompt == null ? "" : prompt.split("[,.;]")[0].strip();
        if (subject.length() > 60) subject = subject.substring(0, 59) + "…";
        if (subject.isEmpty()) return out;
        Graphics2D g = pen(out);
        int w = out.getWidth(), h = out.getHeight();
        pill(g, subject, body(Math.max(14, w / 38f)), (int) (w * 0.06), (int) (h * 0.86),
                new Color(0, 0, 0, 140), new Color(255, 255, 255, 230), 18, 10);
        g.dispose();
        return out;
    }

    /** Places the ArtDirector's overlay text on an image in a layout suited to its purpose. */
    public static BufferedImage withOverlay(BufferedImage base, ImageRequest request) {
        BufferedImage out = cover(base, request.width(), request.height());
        String text = request.overlayText();
        if (text == null || text.isBlank()) return out;
        Graphics2D g = pen(out);
        int w = out.getWidth(), h = out.getHeight();
        legibilityScrim(g, w, h);
        boolean portrait = h > w;
        if (portrait) {
            headline(g, text, (int) (w * 0.08), (int) (h * 0.55), (int) (w * 0.84), w * 0.14f, -3, 1f);
        } else {
            headline(g, text, (int) (w * 0.05), (int) (h * 0.68), (int) (w * 0.58), h * 0.19f, -4, 1f);
        }
        g.dispose();
        return out;
    }
}
