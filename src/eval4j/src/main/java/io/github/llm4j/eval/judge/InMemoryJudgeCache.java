package io.github.llm4j.eval.judge;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches judge verdicts for the lifetime of this instance — typically one JVM/test run. Helps when
 * the same {@link LlmJudgeCondition} is evaluated repeatedly within a single run (e.g. inside a
 * {@code PassRate} loop with overlapping scenarios) but doesn't survive across separate {@code mvn
 * test} invocations; use {@link FileSystemJudgeCache} for that.
 */
public final class InMemoryJudgeCache implements JudgeCache {

    private final Map<String, JudgeVerdict> store = new ConcurrentHashMap<>();

    private InMemoryJudgeCache() {}

    public static InMemoryJudgeCache create() {
        return new InMemoryJudgeCache();
    }

    @Override
    public Optional<JudgeVerdict> get(String key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void put(String key, JudgeVerdict verdict) {
        store.put(key, verdict);
    }
}
