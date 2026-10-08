package io.github.llm4j.eval.optimize;

/** What a run consumed. {@code trackedLlmCalls} includes calls through {@link LlmCallCounter}s. */
public record Cost(long rollouts, long rewriterCalls, long trackedLlmCalls, long elapsedMillis) {}
