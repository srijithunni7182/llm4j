package io.github.llm4j.tools.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The text of a document and where each part of it came from (D18, amended): for a PDF, one part
 * per page; for a DOCX, one per paragraph; none for formats without either. A part's offsets index
 * into {@link #text()}, so {@code text.substring(start, end)} is exactly that part's text; parts are
 * in order and do not overlap.
 */
public record Extracted(String format, String text, List<Part> parts, boolean truncated) {

    /** Where one page or paragraph lies in the text. */
    public record Part(String unit, int number, int start, int end) {
        /** Checks the offsets. */
        public Part {
            Objects.requireNonNull(unit, "unit");
            if (number < 1 || start < 0 || end < start)
                throw new IllegalArgumentException("a part is numbered from 1 and its end is not before its start");
        }
    }

    /** Checks that every part lies inside the text, in order. */
    public Extracted {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(text, "text");
        parts = List.copyOf(parts);
        int last = 0;
        for (Part p : parts) {
            if (p.start() < last || p.end() > text.length())
                throw new IllegalArgumentException("parts must lie inside the text, in order");
            last = p.end();
        }
    }

    /**
     * The text cut to at most {@code max} characters: a part past the cut is dropped and the one it
     * falls in is shortened, so the offsets still hold.
     */
    Extracted cut(int max) {
        if (text.length() <= max) return this;
        List<Part> kept = new ArrayList<>();
        for (Part p : parts) {
            if (p.start() >= max) break;
            kept.add(new Part(p.unit(), p.number(), p.start(), Math.min(p.end(), max)));
        }
        return new Extracted(format, text.substring(0, max), kept, true);
    }

    /** Builds a text out of parts, one line break between two parts. */
    static final class Builder {
        private final String unit;
        private final StringBuilder text = new StringBuilder();
        private final List<Part> parts = new ArrayList<>();

        Builder(String unit) {
            this.unit = unit;
        }

        void add(int number, String partText) {
            if (!parts.isEmpty()) text.append('\n');
            int start = text.length();
            text.append(partText);
            parts.add(new Part(unit, number, start, text.length()));
        }

        int length() {
            return text.length();
        }

        Extracted build(String format, boolean truncated) {
            return new Extracted(format, text.toString(), parts, truncated);
        }
    }
}
