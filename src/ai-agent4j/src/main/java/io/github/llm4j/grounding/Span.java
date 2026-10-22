package io.github.llm4j.grounding;

/**
 * A character range in the answer text: the part a {@link Citation} is attached to. {@code start}
 * is inclusive and {@code end} exclusive, in Java string offsets. Many vendors attach the citation
 * to the link the model wrote after a claim, so the range is that link, not the claim.
 */
public record Span(int start, int end) {

    public Span {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException(
                    "a span is 0 <= start <= end, got " + start + ".." + end);
        }
    }

    public int length() {
        return end - start;
    }
}
