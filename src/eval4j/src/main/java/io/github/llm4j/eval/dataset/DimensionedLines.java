package io.github.llm4j.eval.dataset;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

/**
 * Rubric lines that may each name the quality dimension they belong to. It is a plain, read-only
 * {@code List<String>} of the lines' texts, so everything that reads a rubric keeps working; the
 * dimensions ride along and are read with {@link #dimensionOf(int)}.
 */
final class DimensionedLines extends AbstractList<String> {
    private final List<String> texts;
    private final List<String> dimensions;

    private DimensionedLines(List<String> texts, List<String> dimensions) {
        this.texts = texts;
        this.dimensions = dimensions;
    }

    /** The lines of {@code lines} followed by {@code more} (which have no dimension). */
    static List<String> appending(List<String> lines, List<String> more) {
        List<String> texts = new ArrayList<>(lines);
        List<String> dims = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) dims.add(dimensionOf(lines, i));
        for (String line : more) {
            texts.add(line);
            dims.add(null);
        }
        return new DimensionedLines(texts, dims);
    }

    static DimensionedLines of(List<String> texts, List<String> dimensions) {
        return new DimensionedLines(List.copyOf(texts), new ArrayList<>(dimensions));
    }

    /** The dimension of line {@code index} of {@code lines}, or null when it names none. */
    static String dimensionOf(List<String> lines, int index) {
        if (lines instanceof DimensionedLines d && index >= 0 && index < d.dimensions.size())
            return d.dimensions.get(index);
        return null;
    }

    @Override
    public String get(int index) {
        return texts.get(index);
    }

    @Override
    public int size() {
        return texts.size();
    }
}
