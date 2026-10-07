package io.github.llm4j.budget;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.budget.fixtures.LatchedClient;
import io.github.llm4j.budget.fixtures.ScriptedLLMClient;
import io.github.llm4j.budget.fixtures.Standard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Verification plan, Requirement 2.7 (V2.7a–c) and the determinism criterion. */
@Tag("fragile")
class BudgetConcurrencyTest {

    /** V2.7a, repeated with 20 seeds for the determinism criterion. */
    @RepeatedTest(20)
    @Timeout(30)
    void v2_7a_exactlyTheAffordableCallsGetThrough(RepetitionInfo rep) throws Exception {
        Budget b = Budget.builder().tokens(150_000).build();
        ScriptedLLMClient model = new ScriptedLLMClient();
        var client = Standard.client(model, b);
        AtomicInteger refused = new AtomicInteger();
        AtomicLong settled = new AtomicLong();
        client.addChargeListener((m, c) -> settled.addAndGet(c.tokens()));
        Random seed = new Random(rep.getCurrentRepetition());
        runThreads(32, 100, t -> {
            if (seed.nextInt(8) == 0) Thread.yield(); // vary the interleaving per seed
            try {
                client.chat(Standard.request());
            } catch (BudgetExceeded e) {
                refused.incrementAndGet();
            }
        });
        assertThat(model.calls()).isEqualTo(1000);
        assertThat(refused.get()).isEqualTo(2200);
        assertThat(b.spent().tokens()).isEqualTo(150_000).isEqualTo(settled.get());
    }

    @Test
    @Timeout(30)
    void v2_7b_overdrawIsBoundedByTheInFlightPromptErrors() throws Exception {
        Budget b = Budget.builder().tokens(150_000).build();
        LatchedClient model = new LatchedClient(110, 50); // reports 10 more prompt tokens than estimated
        var client = Standard.client(model, b);
        AtomicLong settled = new AtomicLong();
        client.addChargeListener((m, c) -> settled.addAndGet(c.tokens()));
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch done = new CountDownLatch(32);
        for (int t = 0; t < 32; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        try {
                            client.chat(Standard.request());
                        } catch (BudgetExceeded e) {
                            // expected once the budget is spent
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        Thread.sleep(200); // let all 32 reserve and block inside the model
        model.release();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(b.spent().tokens()).isLessThanOrEqualTo(150_000 + 32 * 10).isEqualTo(settled.get());
    }

    @Test
    @Timeout(10)
    void v2_7c_overlappingSetsInAnyOrderNeverDeadlock() throws Exception {
        List<Budget> budgets = List.of(Budget.unlimited("a"), Budget.unlimited("b"),
                Budget.unlimited("c"), Budget.unlimited("d"));
        AtomicLong[] expected = {new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};
        runThreads(16, 10_000 / 16 + 1, t -> {
            List<Budget> pick = new ArrayList<>(budgets);
            Collections.shuffle(pick);
            List<Budget> subset = pick.subList(0, 1 + (int) (Math.random() * 4));
            BudgetSet.Lease lease = BudgetSet.of(subset).reserve(10, 5L, null);
            lease.settle(new Charge(10, 5, 1, BigDecimal.ZERO, false));
            for (Budget b : subset) expected[budgets.indexOf(b)].addAndGet(15);
        });
        for (int i = 0; i < 4; i++) assertThat(budgets.get(i).spent().tokens()).isEqualTo(expected[i].get());
    }

    @Test
    void overheadStaysUnderFiftyMicrosecondsPerCall() {
        Budget b = Budget.builder().tokens(Long.MAX_VALUE / 2).calls(Long.MAX_VALUE / 2).build();
        var client = Standard.client(new ScriptedLLMClient(), b);
        for (int i = 0; i < 20_000; i++) client.chat(Standard.request()); // warm-up
        var bare = new ScriptedLLMClient();
        long t0 = System.nanoTime();
        for (int i = 0; i < 10_000; i++) bare.chat(Standard.request());
        long bareNs = System.nanoTime() - t0;
        long t1 = System.nanoTime();
        for (int i = 0; i < 10_000; i++) client.chat(Standard.request());
        long meteredNs = System.nanoTime() - t1;
        assertThat((meteredNs - bareNs) / 1_000_000).as("metering overhead for 10k calls, ms").isLessThan(500);
    }

    private interface Body {
        void run(int thread) throws Exception;
    }

    private static void runThreads(int threads, int perThread, Body body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) body.run(id);
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) f.get(25, TimeUnit.SECONDS);
        pool.shutdown();
    }
}
