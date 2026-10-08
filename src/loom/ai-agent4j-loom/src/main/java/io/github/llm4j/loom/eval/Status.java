package io.github.llm4j.loom.eval;

/** What became of a check or a scenario. {@code UNJUDGED} is never counted as a pass: nothing confirmed it. */
public enum Status {
    PASS, FAIL, UNJUDGED;

    /** The worst of two: a fail beats unjudged, which beats a pass. */
    public Status and(Status other) {
        return rank() >= other.rank() ? this : other;
    }

    private int rank() {
        return switch (this) {
            case PASS -> 0;
            case UNJUDGED -> 1;
            case FAIL -> 2;
        };
    }
}
