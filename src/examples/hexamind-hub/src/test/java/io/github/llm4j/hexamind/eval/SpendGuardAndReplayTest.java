package io.github.llm4j.hexamind.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpendGuardAndReplayTest {

    private static LLMClient fixed(int in, int out) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest r) {
                return LLMResponse.builder().content("x").tokenUsage(in, out, in + out).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest r) {
                return Stream.of(chat(r));
            }
        };
    }

    private static final LLMRequest REQ = LLMRequest.builder().messages(java.util.List.of(io.github.llm4j.model.Message.user("hi"))).build();

    @Test
    void pricesTokensFromThePricesFile() {
        SpendGuard g = new SpendGuard(Path.of("eval/prices.properties"), 10, 20_000);
        g.guard(fixed(1_000_000, 100_000), "gemini-3.5-flash").chat(REQ);
        assertThat(g.spentUsd()).isEqualTo(2.40, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(g.calls()).isEqualTo(1);
        assertThat(g.prices("claude-sonnet-5-5")).isTrue();
        assertThat(g.prices("unknown")).isFalse();
    }

    @Test
    void stopsTheWholeRunAtTheCap() {
        SpendGuard g = new SpendGuard(Path.of("eval/prices.properties"), 1.0, 20_000);
        LLMClient c = g.guard(fixed(0, 10_000), "gemini-3.5-flash"); // $0.09 per call
        int made = 0;
        while (!g.stopped() && made < 100) {
            c.chat(REQ);
            made++;
        }
        assertThat(g.reason()).contains("cap");
        assertThat(made).isBetween(10, 13);
        assertThatThrownBy(() -> c.chat(REQ)).isInstanceOf(SpendGuard.SpendStopped.class);
        assertThatThrownBy(() -> g.guard(fixed(1, 1), "claude-sonnet-5-5").chat(REQ))
                .isInstanceOf(SpendGuard.SpendStopped.class);
    }

    @Test
    void aStageCeilingStopsTheRunBeforeTheCap() {
        SpendGuard g = new SpendGuard(Path.of("eval/prices.properties"), 10, 20_000);
        g.stage("reasoning", 0.5);
        LLMClient c = g.guard(fixed(0, 10_000), "gemini-3.5-flash"); // $0.09 per call
        for (int i = 0; i < 6 && !g.stopped(); i++) {
            c.chat(REQ);
        }
        assertThat(g.reason()).contains("stage 'reasoning'");
        g.stop("another reason");
        assertThat(g.reason()).contains("stage 'reasoning'");
    }

    @Test
    void aSingleHugeOutputStopsTheRun() {
        SpendGuard g = new SpendGuard(Path.of("eval/prices.properties"), 10, 20_000);
        g.guard(fixed(10, 25_000), "gemini-3.5-flash").chat(REQ);
        assertThat(g.stopped()).isTrue();
        assertThat(g.reason()).contains("25000 output tokens");
    }

    @Test
    void replayKeyIgnoresTheClockAndChangesWithTheInputs(@TempDir Path dir) {
        String a = ReplayCache.key("alex-01", "v1", "gemini-3.5-flash", "CURRENT TIME: 2026-01-01 10:00:00\nfix");
        String b = ReplayCache.key("alex-01", "v1", "gemini-3.5-flash", "CURRENT TIME: 2026-01-01 10:00:09\nfix");
        String c = ReplayCache.key("alex-01", "v2", "gemini-3.5-flash", "CURRENT TIME: 2026-01-01 10:00:00\nfix");
        assertThat(a).isEqualTo(b).isNotEqualTo(c);
        ReplayCache cache = new ReplayCache(dir);
        assertThat(cache.get(a)).isEmpty();
        cache.put(a, "{\"answer\":\"hi\"}");
        assertThat(cache.get(b)).contains("{\"answer\":\"hi\"}");
    }
}
