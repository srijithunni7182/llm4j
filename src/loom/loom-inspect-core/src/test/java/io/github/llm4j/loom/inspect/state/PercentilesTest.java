package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * R8: nearest-rank percentiles, exact up to the capacity and bounded beyond it, with no clock and
 * no randomness from outside.
 */
class PercentilesTest {

    @Test
    void emptyHasNoPercentiles() {
        Percentiles p = new Percentiles();
        assertThat(p.count()).isZero();
        assertThat(p.p50()).isZero();
        assertThat(p.p95()).isZero();
        assertThat(p.max()).isZero();
    }

    @Test
    void oneSampleIsEveryPercentile() {
        Percentiles p = new Percentiles();
        p.add(42);
        assertThat(p.p50()).isEqualTo(42);
        assertThat(p.p95()).isEqualTo(42);
        assertThat(p.max()).isEqualTo(42);
    }

    @Test
    void nearestRankOverOneToHundred() {
        Percentiles p = new Percentiles();
        for (int i = 100; i >= 1; i--) p.add(i);
        assertThat(p.p50()).isEqualTo(50);
        assertThat(p.p95()).isEqualTo(95);
        assertThat(p.percentile(100)).isEqualTo(100);
        assertThat(p.percentile(1)).isEqualTo(1);
        assertThat(p.count()).isEqualTo(100);
    }

    @Test
    void tiesAreFine() {
        Percentiles p = new Percentiles();
        for (int i = 0; i < 10; i++) p.add(7);
        assertThat(p.p50()).isEqualTo(7);
        assertThat(p.p95()).isEqualTo(7);
    }

    @Test
    void negativeValuesCountAsZero() {
        Percentiles p = new Percentiles();
        p.add(-5);
        assertThat(p.max()).isZero();
    }

    @Test
    void beyondTheCapacityMemoryIsBoundedAndTheCountAndMaxStayExact() {
        Percentiles p = new Percentiles();
        int n = 200_000;
        for (int i = 1; i <= n; i++) p.add(i % 1000);
        assertThat(p.count()).isEqualTo(n);
        assertThat(p.retained()).isLessThanOrEqualTo(Percentiles.CAPACITY);
        assertThat(p.max()).isEqualTo(999);
        assertThat(p.p50()).isBetween(400L, 600L);
        assertThat(p.p95()).isBetween(900L, 999L);
    }

    @Test
    void theSameInputsGiveTheSameAnswers() {
        Percentiles a = new Percentiles();
        Percentiles b = new Percentiles();
        for (int i = 1; i <= 50_000; i++) {
            a.add((i * 31L) % 977);
            b.add((i * 31L) % 977);
        }
        assertThat(a.p50()).isEqualTo(b.p50());
        assertThat(a.p95()).isEqualTo(b.p95());
    }
}
