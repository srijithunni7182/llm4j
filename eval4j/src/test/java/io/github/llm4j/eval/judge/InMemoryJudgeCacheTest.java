package io.github.llm4j.eval.judge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InMemoryJudgeCacheTest {

    @Test
    void get_isEmptyForAnUnknownKey() {
        InMemoryJudgeCache cache = InMemoryJudgeCache.create();
        assertThat(cache.get("missing")).isEmpty();
    }

    @Test
    void put_thenGet_returnsTheStoredVerdict() {
        InMemoryJudgeCache cache = InMemoryJudgeCache.create();
        JudgeVerdict verdict = new JudgeVerdict(0.75, "[4/5] good");

        cache.put("key1", verdict);

        assertThat(cache.get("key1")).contains(verdict);
    }

    @Test
    void put_overwritesAnExistingEntryForTheSameKey() {
        InMemoryJudgeCache cache = InMemoryJudgeCache.create();
        cache.put("key1", new JudgeVerdict(0.25, "old"));
        cache.put("key1", new JudgeVerdict(1.0, "new"));

        assertThat(cache.get("key1")).contains(new JudgeVerdict(1.0, "new"));
    }

    @Test
    void differentInstances_doNotShareState() {
        InMemoryJudgeCache first = InMemoryJudgeCache.create();
        InMemoryJudgeCache second = InMemoryJudgeCache.create();
        first.put("key1", new JudgeVerdict(1.0, "only in first"));

        assertThat(second.get("key1")).isEmpty();
    }
}
