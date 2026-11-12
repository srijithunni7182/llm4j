package io.github.llm4j.loom.execution;

/**
 * What a host allows a tree of started workflows (decision D14): the most children a run may have
 * live at once and how many levels deep they may nest. A script may lower either with {@code
 * max_children} and {@code max_depth}, or raise it up to the ceiling here; asking for more is
 * refused, never quietly cut.
 *
 * @param ceilingChildren the most live children a script may ask for
 * @param ceilingDepth the most levels below the top workflow a script may ask for
 * @param settleGrace how long a parent that ends waits for the children it cancelled to settle
 *     (decision D10); one still going after it is recorded as cancelled with a note
 */
public record ChildLimits(int ceilingChildren, int ceilingDepth, java.time.Duration settleGrace) {

    /** How long a parent waits for its cancelled children when the host says nothing. */
    public static final java.time.Duration DEFAULT_SETTLE_GRACE = java.time.Duration.ofSeconds(30);

    /** The ceilings, with the default grace period. */
    public ChildLimits(int ceilingChildren, int ceilingDepth) {
        this(ceilingChildren, ceilingDepth, DEFAULT_SETTLE_GRACE);
    }

    /** Live children a run may have when its script says nothing. */
    public static final int DEFAULT_MAX_CHILDREN = 8;

    /** Levels below the top workflow when the script says nothing. */
    public static final int DEFAULT_MAX_DEPTH = 2;

    /** What a host allows when it says nothing: a script may go to four times the defaults. */
    public static final ChildLimits HOST_DEFAULT =
            new ChildLimits(4 * DEFAULT_MAX_CHILDREN, 4 * DEFAULT_MAX_DEPTH);

    public ChildLimits {
        java.util.Objects.requireNonNull(settleGrace, "settleGrace");
        if (settleGrace.isNegative())
            throw new IllegalArgumentException("the grace period cannot be negative: " + settleGrace);
        if (ceilingChildren < 1 || ceilingDepth < 1)
            throw new IllegalArgumentException(
                    "the ceilings must be at least 1, got " + ceilingChildren + " and " + ceilingDepth);
    }
}
