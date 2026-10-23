package io.github.llm4j.loom.review.cost;

/**
 * How many outward effects one workflow can cause, from the tools its agents hold: calls to a tool
 * that sends something out ({@code sends}) and calls to a tool that changes something ({@code
 * writes}). A retry repeats the effect, so the worst case counts every attempt.
 *
 * @param sends calls to tools that send
 * @param writes calls to tools that write
 */
public record EffectsEstimate(Count sends, Count writes) {

    /**
     * A count: what a normal run does, and the most it can do.
     *
     * @param typical a normal run
     * @param worst the most; only the bounded part when {@code unbounded}
     * @param unbounded true when something that causes the effect can repeat without limit
     */
    public record Count(long typical, long worst, boolean unbounded) {
        public static final Count NONE = new Count(0, 0, false);

        public boolean none() {
            return typical == 0 && worst == 0 && !unbounded;
        }
    }

    public boolean none() {
        return sends.none() && writes.none();
    }
}
