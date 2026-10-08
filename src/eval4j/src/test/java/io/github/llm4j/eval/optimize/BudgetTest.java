package io.github.llm4j.eval.optimize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.eval.support.JudgeResponses;
import io.github.llm4j.eval.support.StubJudge;
import io.github.llm4j.model.LLMRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BudgetTest {

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private volatile long millis;

        void advance(Duration d) {
            millis += d.toMillis();
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    private static BudgetTracker tracker(
            OptimizerBudget budget, MutableClock clock, LlmCallCounter... c) {
        return new BudgetTracker(budget, clock, List.of(c));
    }

    @Test
    void builderRequiresPositiveCapsAndReportsWhetherAnyIsSet() {
        assertThat(OptimizerBudget.builder().build().hasAnyCap()).isFalse();
        assertThat(OptimizerBudget.builder().maxRounds(3).build().hasAnyCap()).isTrue();
        assertThatThrownBy(() -> OptimizerBudget.builder().maxRollouts(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OptimizerBudget.builder().maxDuration(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OptimizerBudget.builder().maxLlmCalls(-1))
                .isInstanceOf(IllegalArgumentException.class);
        OptimizerBudget b =
                OptimizerBudget.builder()
                        .maxRollouts(10)
                        .maxRounds(2)
                        .maxLlmCalls(5)
                        .maxDuration(Duration.ofSeconds(1))
                        .build();
        assertThat(b.maxRollouts()).isEqualTo(10);
        assertThat(b.maxRounds()).isEqualTo(2);
        assertThat(b.maxLlmCalls()).isEqualTo(5);
        assertThat(b.maxDuration()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void rolloutsAreAcquiredAllOrNothingLeavingTheReserve() {
        BudgetTracker t =
                tracker(OptimizerBudget.builder().maxRollouts(10).build(), new MutableClock());

        assertThat(t.tryAcquireRollouts(4, 0)).isTrue();
        assertThat(t.tryAcquireRollouts(4, 3)).isFalse(); // 4+4+3 > 10
        assertThat(t.rollouts()).isEqualTo(4);
        assertThat(t.tryAcquireRollouts(4, 2)).isTrue(); // 4+4+2 == 10
        assertThat(t.tryAcquireRollouts(1, 2)).isFalse(); // loop work may not touch the reserve
        assertThat(t.tryAcquireRollouts(2, 0)).isTrue(); // later phases may use it
        assertThat(t.tryAcquireRollouts(1, 0)).isFalse();
        assertThat(t.rollouts()).isEqualTo(10);
    }

    @Test
    void unlimitedRolloutsAreStillCounted() {
        BudgetTracker t =
                tracker(OptimizerBudget.builder().maxRounds(1).build(), new MutableClock());
        assertThat(t.tryAcquireRollouts(1000, 0)).isTrue();
        assertThat(t.rollouts()).isEqualTo(1000);
    }

    @Test
    void acquisitionIsAtomicUnderContention() throws Exception {
        BudgetTracker t =
                tracker(OptimizerBudget.builder().maxRollouts(500).build(), new MutableClock());
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger granted = new AtomicInteger();
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    if (t.tryAcquireRollouts(1, 0)) {
                                        granted.incrementAndGet();
                                    }
                                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(granted.get()).isEqualTo(500);
        assertThat(t.rollouts()).isEqualTo(500);
    }

    @Test
    void durationIsMeasuredWithTheInjectedClock() {
        MutableClock clock = new MutableClock();
        BudgetTracker t =
                tracker(
                        OptimizerBudget.builder().maxDuration(Duration.ofMinutes(5)).build(),
                        clock);

        assertThat(t.exhausted(0)).isEmpty();
        clock.advance(Duration.ofMinutes(5));
        assertThat(t.exhausted(0)).contains(StopReason.MAX_DURATION);
        assertThat(t.elapsedMillis()).isEqualTo(Duration.ofMinutes(5).toMillis());
    }

    @Test
    void roundsAndLlmCallsTripIndependently() {
        StubJudge stub = StubJudge.always(JudgeResponses.rating(5, "x"));
        LlmCallCounter counter = LlmCallCounter.wrap(stub);
        BudgetTracker t =
                tracker(
                        OptimizerBudget.builder().maxRounds(3).maxLlmCalls(4).build(),
                        new MutableClock(),
                        counter);

        assertThat(t.exhausted(2)).isEmpty();
        assertThat(t.exhausted(3)).contains(StopReason.MAX_ROUNDS);

        counter.chat(LLMRequest.builder().addUserMessage("hi").build());
        counter.chat(LLMRequest.builder().addUserMessage("hi").build());
        t.recordRewriterCall();
        assertThat(t.llmCalls()).isEqualTo(3);
        assertThat(t.exhausted(0)).isEmpty();
        t.recordRewriterCall();
        assertThat(t.exhausted(0)).contains(StopReason.MAX_LLM_CALLS);
        assertThat(t.rewriterCalls()).isEqualTo(2);
    }

    @Test
    void restoreContinuesTheSameBudget() {
        MutableClock clock = new MutableClock();
        BudgetTracker t =
                tracker(
                        OptimizerBudget.builder()
                                .maxRollouts(10)
                                .maxDuration(Duration.ofMinutes(10))
                                .build(),
                        clock);

        t.restore(7, 3, Duration.ofMinutes(9).toMillis());
        clock.advance(Duration.ofMinutes(2));

        assertThat(t.rollouts()).isEqualTo(7);
        assertThat(t.rewriterCalls()).isEqualTo(3);
        assertThat(t.tryAcquireRollouts(4, 0)).isFalse();
        assertThat(t.exhausted(0)).contains(StopReason.MAX_DURATION);
    }

    @Test
    void callCounterCountsChatAndStreamAndRejectsNull() {
        LlmCallCounter counter = LlmCallCounter.wrap(StubJudge.always("x"));
        LLMRequest request = LLMRequest.builder().addUserMessage("hi").build();
        counter.chat(request);
        counter.chatStream(request).count();
        assertThat(counter.count()).isEqualTo(2);
        assertThatThrownBy(() -> LlmCallCounter.wrap(null))
                .isInstanceOf(NullPointerException.class);
    }
}
