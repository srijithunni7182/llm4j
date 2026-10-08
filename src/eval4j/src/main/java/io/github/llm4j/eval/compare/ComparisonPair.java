package io.github.llm4j.eval.compare;

/**
 * Two outputs to compare for one input, for use with {@link PairwiseCondition} inside a plain
 * {@code @ParameterizedTest}. Each output may be an {@code AgentResult}, {@code LLMResponse} or
 * {@code String}.
 */
public record ComparisonPair(String input, Object a, Object b) {}
