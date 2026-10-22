package io.github.llm4j.context;

import io.github.llm4j.exception.ContextOverflowException;
import io.github.llm4j.exception.InvalidRequestException;
import java.util.List;
import java.util.Locale;

/**
 * Tells "the prompt is too long" apart from every other bad request. Providers say it in their own
 * words and none of them give it a dedicated status, so this reads the error text. The phrases are
 * in one place, with a test that holds both bodies that must match and bodies that must not (an
 * output limit, a bad parameter, a quota, a rate limit). An unknown phrasing falls back to the
 * ordinary {@link InvalidRequestException}: nothing is lost, the agent just cannot recover by
 * itself.
 */
public final class ContextOverflowClassifier {

    private static final List<String> PHRASES =
            List.of(
                    "context_length_exceeded",
                    "model_context_window_exceeded",
                    "maximum context length",
                    "context length exceeded",
                    "exceeds the context window",
                    "exceed the context window",
                    "exceeds the available context size",
                    "exceed_context_size_error",
                    "exceed context limit",
                    "exceeds the context limit",
                    "prompt is too long",
                    "prompt too long",
                    "input is too long",
                    "input too long",
                    "too many tokens in the prompt",
                    "reduce the length of the messages",
                    "reduce the length of the prompt",
                    "request_too_large");

    private ContextOverflowClassifier() {}

    /**
     * @param status the HTTP status, or null for an error found inside a stream or a 200 response
     * @param text the response body and/or the error message
     */
    public static boolean isOverflow(Integer status, String text) {
        if (text == null || text.isBlank()) return false;
        if (status != null && status != 400 && status != 413 && status != 422) return false;
        String t = text.toLowerCase(Locale.ROOT);
        for (String phrase : PHRASES) if (t.contains(phrase)) return true;
        // Google: "The input token count (N) exceeds the maximum number of tokens allowed (M)."
        return t.contains("input token count") && t.contains("exceeds the maximum");
    }

    /**
     * A {@link ContextOverflowException} when the error is about the prompt's length, otherwise an
     * ordinary {@link InvalidRequestException}.
     */
    public static InvalidRequestException invalidRequest(
            String message, Integer status, String body) {
        boolean overflow = isOverflow(status, body) || isOverflow(status, message);
        if (overflow) return new ContextOverflowException(message, status, body);
        return status == null && body == null
                ? new InvalidRequestException(message)
                : new InvalidRequestException(message, status == null ? 400 : status, body);
    }
}
