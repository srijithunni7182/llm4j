package io.github.llm4j.grounding;

import java.util.List;
import java.util.Objects;

/**
 * A source a grounded answer is attributed to. What the vendor returned is kept as it came: the URL
 * as given (even empty or relative), a missing title or snippet stays null.
 *
 * @param index the position in {@code LLMResponse.getCitations()}, counting from 1 (as in a "[1]"
 *     marker); stable within a response
 * @param url the source's address as the vendor returned it
 * @param title the page title, or null when the vendor gave none
 * @param snippet the quoted or summarised text, or null
 * @param spans where in the answer the source is cited; empty when the vendor gives no positions.
 *     Never outside the answer text
 */
public record Citation(int index, String url, String title, String snippet, List<Span> spans) {

    public Citation {
        if (index < 1)
            throw new IllegalArgumentException("a citation index counts from 1, got " + index);
        Objects.requireNonNull(url, "url cannot be null");
        spans = spans == null ? List.of() : List.copyOf(spans);
    }

    /**
     * The same citation with only the spans that lie within {@code answer} (the others are dropped,
     * not clipped).
     */
    public Citation withSpansWithin(String answer) {
        int length = answer == null ? 0 : answer.length();
        List<Span> kept = spans.stream().filter(s -> s.end() <= length).toList();
        return kept.size() == spans.size() ? this : new Citation(index, url, title, snippet, kept);
    }
}
