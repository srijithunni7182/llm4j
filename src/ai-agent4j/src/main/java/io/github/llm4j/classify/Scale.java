package io.github.llm4j.classify;

import java.util.HashSet;
import java.util.List;

/**
 * The ordered points of a {@link Question.Score}, lowest first. A scale has 2 to 10 points,
 * whichever backend answers: 10 is Jev's limit, and 2 is what makes a position along the scale
 * defined, so a script behaves the same on either backend.
 *
 * @param points the point labels in order; unique and not blank
 */
public record Scale(List<String> points) {

    /** Fewest points a scale may have. */
    public static final int MIN_POINTS = 2;

    /** Most points a scale may have. */
    public static final int MAX_POINTS = 10;

    public Scale {
        points = points == null ? List.of() : List.copyOf(points);
        if (points.size() < MIN_POINTS || points.size() > MAX_POINTS) {
            throw new IllegalArgumentException(
                    "a scale needs "
                            + MIN_POINTS
                            + " to "
                            + MAX_POINTS
                            + " points, got "
                            + points.size());
        }
        if (points.stream().anyMatch(p -> p == null || p.isBlank()))
            throw new IllegalArgumentException("a scale point cannot be blank");
        if (new HashSet<>(points).size() != points.size())
            throw new IllegalArgumentException("scale points must be unique: " + points);
    }

    public static Scale of(String... points) {
        return new Scale(List.of(points));
    }

    public int size() {
        return points.size();
    }

    /** Where a point sits along the scale: 0.0 for the first, 1.0 for the last. */
    public double positionOf(String point) {
        int i = points.indexOf(point);
        if (i < 0) throw new IllegalArgumentException("not a point of this scale: " + point);
        return (double) i / (points.size() - 1);
    }

    /** The point closest to a position in [0,1]. */
    public String nearest(double position) {
        int i = (int) Math.round(position * (points.size() - 1));
        return points.get(Math.max(0, Math.min(points.size() - 1, i)));
    }
}
