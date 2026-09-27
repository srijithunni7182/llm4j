package io.github.llm4j.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.budget.fixtures.ScriptedLLMClient;
import io.github.llm4j.budget.fixtures.Standard;
import io.github.llm4j.ratelimit.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Verification plan V3.1–V3.7. */
class WindowedBudgetTest {

    private final MutableClock clock = new MutableClock();

    private Budget hourly(long tokens) {
        return Budget.builder().name("hourly").tokens(tokens).window(Window.HOUR).clock(clock).build();
    }

    private static Spent spent(long prompt, long completion) {
        return new Spent(prompt, completion, 1, BigDecimal.ZERO, false, 0);
    }

    @Test
    void v3_1_spendStartsOverWhenTheWindowRollsOver() {
        Budget b = hourly(1000);
        clock.set("2026-09-27T10:10:00Z");
        BudgetSet.of(b).reserve(900, 0L, null).settle(new Charge(900, 0, 1, BigDecimal.ZERO, false));
        clock.set("2026-09-27T10:59:59Z");
        assertThat(b.remaining().tokens().getAsLong()).isEqualTo(100);
        clock.set("2026-09-27T11:00:00Z");
        assertThat(b.remaining().tokens().getAsLong()).isEqualTo(1000);
        assertThat(b.spent().tokens()).isZero();
        assertThat(b.lifetimeSpent().tokens()).isEqualTo(900);
        assertThat(b.lifetimeSpent().calls()).isEqualTo(1);
    }

    @Test
    void v3_2_refusalSaysWhenTheBudgetRefills() {
        Budget b = hourly(200);
        clock.set("2026-09-27T10:30:00Z");
        var client = Standard.client(new ScriptedLLMClient(), b);
        client.chat(Standard.request()); // 150 of 200
        assertThatThrownBy(() -> client.chat(Standard.request()))
                .isInstanceOfSatisfying(BudgetExceeded.class, e -> {
                    assertThat(e.resetAt()).contains(Instant.parse("2026-09-27T11:00:00Z"));
                    assertThat(e.getMessage()).contains("refills at 2026-09-27T11:00:00Z");
                });
        assertThat(b.windowEnd()).isEqualTo(Instant.parse("2026-09-27T11:00:00Z"));
        assertThat(b.window()).isEqualTo(Window.HOUR);

        Budget lifetime = Budget.builder().name("life").tokens(200).build();
        var client2 = Standard.client(new ScriptedLLMClient(), lifetime);
        client2.chat(Standard.request());
        assertThatThrownBy(() -> client2.chat(Standard.request()))
                .isInstanceOfSatisfying(BudgetExceeded.class, e -> assertThat(e.resetAt()).isEmpty());
        assertThat(lifetime.windowEnd()).isNull();
    }

    @Test
    void v3_2b_afterTheRefillCallsAreAllowedAgain() {
        Budget b = hourly(200);
        var client = Standard.client(new ScriptedLLMClient(), b);
        client.chat(Standard.request());
        assertThatThrownBy(() -> client.chat(Standard.request())).isInstanceOf(BudgetExceeded.class);
        assertThat(b.refused()).isTrue();
        clock.set("2026-09-27T11:00:00Z");
        assertThat(b.refused()).isFalse();
        client.chat(Standard.request());
        assertThat(b.spent().tokens()).isEqualTo(150);
        assertThat(b.lifetimeSpent().tokens()).isEqualTo(300);
    }

    @Test
    void v3_3_dayWindowFollowsTheClockZone() {
        MutableClock kolkata = new MutableClock(MutableClock.T0, ZoneId.of("Asia/Kolkata"));
        Budget b = Budget.builder().name("daily").tokens(10).window(Window.DAY).clock(kolkata).build();
        assertThat(b.windowEnd()).isEqualTo(Instant.parse("2026-09-27T18:30:00Z"));
        assertThat(Window.MINUTE.end(MutableClock.T0.plusSeconds(61), ZoneId.of("UTC")))
                .isEqualTo(Instant.parse("2026-09-27T10:02:00Z"));
        assertThat(Window.parse("Day")).isEqualTo(Window.DAY);
    }

    @Test
    void v3_4_warningFiresOncePerWindow() {
        List<BudgetEvent> events = new ArrayList<>();
        Budget b = Budget.builder().name("w").tokens(1000).window(Window.HOUR).clock(clock).warnAt(0.5)
                .listener(events::add).build();
        b.restore(spent(600, 0));
        b.restore(spent(100, 0));
        clock.set("2026-09-27T11:00:00Z");
        b.restore(spent(600, 0));
        assertThat(events).extracting(BudgetEvent::kind).containsExactly(BudgetEvent.Kind.WARNING, BudgetEvent.Kind.WARNING);
    }

    @Test
    void v3_5_aReservationInFlightAcrossTheRollOverLandsInTheNewWindow() {
        Budget b = hourly(1000);
        clock.set("2026-09-27T10:59:59Z");
        b.restore(spent(500, 0));
        BudgetSet.Lease lease = BudgetSet.of(b).reserve(100, 50L, null);
        clock.set("2026-09-27T11:00:01Z");
        lease.settle(new Charge(100, 40, 1, BigDecimal.ZERO, false));
        assertThat(b.spent().tokens()).isEqualTo(140);
        assertThat(b.lifetimeSpent().tokens()).isEqualTo(640);
        assertThat(b.remaining().tokens().getAsLong()).isEqualTo(860);
        // reserved counters are back to zero: a full-window reservation fits
        BudgetSet.of(b).reserve(800, 50L, null).release();
    }

    @Test
    void v3_6_concurrentCallsAcrossRollOversNeverOverdrawAWindow() throws Exception {
        Budget b = Budget.builder().name("m").tokens(1000).window(Window.MINUTE).clock(clock).build();
        AtomicLong charged = new AtomicLong();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong maxSeen = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < 32; t++) {
            pool.submit(() -> {
                go.await();
                while (!done.get()) {
                    try {
                        BudgetSet.Lease lease = BudgetSet.of(b).reserve(40, 10L, null);
                        lease.settle(new Charge(40, 10, 1, BigDecimal.ZERO, false));
                        charged.addAndGet(50);
                    } catch (BudgetExceeded refused) {
                        Thread.onSpinWait();
                    }
                    maxSeen.accumulateAndGet(b.spent().tokens(), Math::max);
                }
                return null;
            });
        }
        go.countDown();
        for (int minute = 1; minute <= 20; minute++) {
            Thread.sleep(3);
            clock.set(MutableClock.T0.plusSeconds(60L * minute));
        }
        Thread.sleep(3);
        done.set(true);
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(maxSeen.get()).isLessThanOrEqualTo(1000);
        assertThat(b.lifetimeSpent().tokens()).isEqualTo(charged.get());
        assertThat(b.lifetimeSpent().tokens()).isGreaterThan(5 * 1000); // several windows were really used
    }

    @Test
    void v3_7_restoreIgnoresSpendFromEarlierWindows() {
        Budget b = Budget.builder().name("d").tokens(1000).window(Window.DAY).clock(clock).build();
        b.restore(spent(400, 0), Instant.parse("2026-09-26T23:00:00Z"));
        assertThat(b.remaining().tokens().getAsLong()).isEqualTo(1000);
        assertThat(b.lifetimeSpent().tokens()).isEqualTo(400);
        b.restore(spent(300, 0), Instant.parse("2026-09-27T01:00:00Z"));
        assertThat(b.remaining().tokens().getAsLong()).isEqualTo(700);
        // lifetime budgets count everything, whatever the time
        Budget life = Budget.builder().name("l").tokens(1000).build();
        life.restore(spent(400, 0), Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(life.remaining().tokens().getAsLong()).isEqualTo(600);
        assertThat(b.toString()).contains("per day");
    }
}
