package io.github.llm4j.engram.benchmark;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * A decorating LLMClient that intercepts every call and aggregates:
 * <ul>
 *   <li>Total prompt (input) tokens</li>
 *   <li>Total completion (output) tokens</li>
 *   <li>Total number of API calls</li>
 *   <li>Total and average latency (ms)</li>
 * </ul>
 *
 * If the provider returns real token counts in {@link LLMResponse#getTokenUsage()},
 * those are used verbatim. Otherwise we fall back to a whitespace-based estimate
 * (4 chars ≈ 1 token) so the benchmark always produces a number.
 */
public class TrackingLLMClient implements LLMClient {

    private final LLMClient delegate;
    private final long sleepMs;

    private final AtomicInteger callCount     = new AtomicInteger(0);
    private final AtomicLong promptTokens     = new AtomicLong(0);
    private final AtomicLong completionTokens = new AtomicLong(0);
    private final AtomicLong totalLatencyMs   = new AtomicLong(0);

    public TrackingLLMClient(LLMClient delegate) {
        this(delegate, 0);
    }

    public TrackingLLMClient(LLMClient delegate, long sleepMs) {
        this.delegate = delegate;
        this.sleepMs = sleepMs;
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        // Rate limiting: sleep if configured
        if (sleepMs > 0) {
            try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) {}
        }

        System.out.println("[DEBUG] TrackingLLMClient: Sending request to " + delegate.getClass().getSimpleName());
        long start    = System.currentTimeMillis();
        LLMResponse response;
        try {
            response = delegate.chat(request);
        } catch (Exception e) {
            System.err.println("[ERROR] TrackingLLMClient: API call failed: " + e.getMessage());
            // Log the messages that caused the failure
            request.getMessages().forEach(m -> System.err.println("  Role: " + m.getRole() + " | Content: " + m.getContent()));
            throw e;
        }
        long elapsed  = System.currentTimeMillis() - start;
        System.out.println("[DEBUG] TrackingLLMClient: Received response in " + elapsed + "ms");

        callCount.incrementAndGet();
        totalLatencyMs.addAndGet(elapsed);

        LLMResponse.TokenUsage usage = response.getTokenUsage();
        if (usage != null) {
            promptTokens.addAndGet(usage.getPromptTokens());
            completionTokens.addAndGet(usage.getCompletionTokens());
        } else {
            // Fallback approximation
            String allInput = request.getMessages().stream()
                    .map(m -> m.getContent() != null ? m.getContent() : "")
                    .reduce("", (a, b) -> a + " " + b);
            promptTokens.addAndGet(estimateTokens(allInput));
            completionTokens.addAndGet(estimateTokens(response.getContent()));
        }

        return response;
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return delegate.chatStream(request);
    }

    // -----------------------------------------------------------------------
    // Metrics
    // -----------------------------------------------------------------------

    public int  getCallCount()         { return callCount.get(); }
    public long getPromptTokens()      { return promptTokens.get(); }
    public long getCompletionTokens()  { return completionTokens.get(); }
    public long getTotalTokens()       { return promptTokens.get() + completionTokens.get(); }
    public long getTotalLatencyMs()    { return totalLatencyMs.get(); }
    public double getAvgLatencyMs()    {
        int calls = callCount.get();
        return calls == 0 ? 0.0 : (double) totalLatencyMs.get() / calls;
    }

    /** Resets all counters — call between benchmark runs. */
    public void reset() {
        callCount.set(0);
        promptTokens.set(0);
        completionTokens.set(0);
        totalLatencyMs.set(0);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1L, text.length() / 4);
    }
}
