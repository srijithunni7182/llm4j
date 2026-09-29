package io.github.llm4j.provider;

import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.model.LLMResponse;

/** Small helpers every provider uses to keep the contract uniform. */
public final class Providers {

    /** Metadata key holding the provider's own finish reason ({@code end_turn}, {@code MAX_TOKENS}, …). */
    public static final String FINISH_REASON_RAW = "finish_reason_raw";

    private Providers() {}

    /**
     * Keeps a typed failure as it is — authentication, invalid request, rate limit, or any provider
     * failure (unavailable, content blocked) — so callers can tell them apart whichever provider they
     * use; wraps anything else (parsing errors, I/O) in a {@link ProviderException} as before.
     */
    public static RuntimeException typed(String provider, String message, Exception e) {
        if (e instanceof AuthenticationException
                || e instanceof InvalidRequestException
                || e instanceof RateLimitException
                || e instanceof ProviderException) {
            return (RuntimeException) e;
        }
        return new ProviderException(provider, message, e);
    }

    /** One piece of streamed text. */
    public static LLMResponse textChunk(String text, String model) {
        return LLMResponse.builder().content(text).model(model).build();
    }

    /**
     * The last chunk of a stream: no text, the (normalised) finish reason with the provider's raw value,
     * and the token usage when the provider reported it (null counts as unreported).
     */
    public static LLMResponse finalChunk(String rawFinishReason, Integer promptTokens, Integer completionTokens,
                                         String model) {
        LLMResponse.Builder b = LLMResponse.builder().content("").model(model).finishReason(rawFinishReason);
        if (rawFinishReason != null) b.addMetadata(FINISH_REASON_RAW, rawFinishReason);
        if (promptTokens != null || completionTokens != null) {
            int p = promptTokens == null ? 0 : promptTokens;
            int c = completionTokens == null ? 0 : completionTokens;
            b.tokenUsage(p, c, p + c);
        }
        return b.build();
    }
}
