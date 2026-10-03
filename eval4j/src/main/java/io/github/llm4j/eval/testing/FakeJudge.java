package io.github.llm4j.eval.testing;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.util.stream.Stream;

/**
 * A judge that never calls a model, so a whole evaluation, its cache and its report can be
 * exercised for free before a paid run. Its verdicts are deterministic and meaningless: do not read
 * results from it.
 */
public final class FakeJudge implements LLMClient {

    private final int fixedRating;

    private FakeJudge(int fixedRating) {
        this.fixedRating = fixedRating;
    }

    /** Rates every answer {@code rating} out of 5 and calls pairwise comparisons a tie. */
    public static FakeJudge rating(int rating) {
        return new FakeJudge(rating);
    }

    /**
     * Rates 3 to 5 depending on the text and picks pairwise winners the same way, so reports show a
     * spread.
     */
    public static FakeJudge varied() {
        return new FakeJudge(0);
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        StringBuilder sb = new StringBuilder();
        for (Message m : request.getMessages()) {
            sb.append(m.getContent()).append('\n');
        }
        String t = sb.toString();
        int h = Math.abs(t.hashCode());
        String out;
        if (t.contains("\"winner\"")) {
            String[] w =
                    fixedRating > 0 ? new String[] {"TIE"} : new String[] {"A", "B", "B", "TIE"};
            out =
                    "```json\n{\"reasoning\": \"fake\", \"winner\": \""
                            + w[h % w.length]
                            + "\"}\n```";
        } else {
            int rating = fixedRating > 0 ? fixedRating : 3 + h % 3;
            out = "```json\n{\"reasoning\": \"fake verdict\", \"rating\": " + rating + "}\n```";
        }
        return LLMResponse.builder()
                .content(out)
                .model("fake-judge")
                .tokenUsage(Math.max(1, t.length() / 4), Math.max(1, out.length() / 4), 0)
                .build();
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return Stream.of(chat(request));
    }
}
