package io.github.llm4j.eval.judge;

import java.util.Optional;

/**
 * Pluggable cache for judge verdicts. {@link LlmJudgeCondition} keys entries by the exact content
 * of a judge call — criterion, inputs, and the output being judged — so a cache hit only ever
 * occurs when the same call would be made again; there's no separate invalidation to manage, and
 * switching the judge model, criteria, or the thing under test naturally produces a different key.
 * Not applied unless a condition is built with {@link LlmJudgeCondition.Builder#cache(JudgeCache)}.
 */
public interface JudgeCache {

    Optional<JudgeVerdict> get(String key);

    void put(String key, JudgeVerdict verdict);
}
