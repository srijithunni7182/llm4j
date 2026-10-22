package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Latency percentiles: nearest-rank, exact up to the reservoir's size, bounded after it (R16). */
class LatencyStatsTest {

    private static LatencyStats of(long... ms) {
        LatencyRecorder r = new LatencyRecorder();
        for (long v : ms) r.add(v);
        return r.snapshot();
    }

    @Test
    void noSamplesIsEmptyNotZero() {
        LatencyStats s = new LatencyRecorder().snapshot();
        assertThat(s.isEmpty()).isTrue();
        assertThat(s.count()).isZero();
        assertThat(LatencyStats.EMPTY).isEqualTo(s);
    }

    @Test
    void oneSampleIsEveryPercentile() {
        LatencyStats s = of(250);
        assertThat(s.isEmpty()).isFalse();
        assertThat(s)
                .extracting(
                        LatencyStats::count,
                        LatencyStats::min,
                        LatencyStats::max,
                        LatencyStats::p50,
                        LatencyStats::p90,
                        LatencyStats::p95,
                        LatencyStats::p99)
                .containsExactly(1L, 250L, 250L, 250L, 250L, 250L, 250L);
        assertThat(s.mean()).isEqualTo(250.0);
    }

    @Test
    void nearestRankOnSmallSets() {
        // nearest rank: the value at position ceil(p/100 * n) of the sorted samples
        LatencyStats two = of(10, 20);
        assertThat(two.p50()).isEqualTo(10); // ceil(0.5 * 2) = 1
        assertThat(two.p90()).isEqualTo(20); // ceil(1.8) = 2
        LatencyStats five = of(50, 10, 40, 20, 30); // unsorted on purpose
        assertThat(five.p50()).isEqualTo(30); // ceil(2.5) = 3
        assertThat(five.p90()).isEqualTo(50); // ceil(4.5) = 5
        assertThat(five.p95()).isEqualTo(50);
        assertThat(five.min()).isEqualTo(10);
        assertThat(five.max()).isEqualTo(50);
        assertThat(five.mean()).isEqualTo(30.0);
    }

    @Test
    void aHundredSamplesGiveTheValueAtThatRank() {
        long[] v = new long[100];
        for (int i = 0; i < 100; i++) v[i] = i + 1; // 1..100
        LatencyStats s = of(v);
        assertThat(s.p50()).isEqualTo(50);
        assertThat(s.p90()).isEqualTo(90);
        assertThat(s.p95()).isEqualTo(95);
        assertThat(s.p99()).isEqualTo(99);
    }

    @Test
    void tiesAreFine() {
        LatencyStats s = of(5, 5, 5, 5, 100);
        assertThat(s.p50()).isEqualTo(5);
        assertThat(s.p90()).isEqualTo(100);
    }

    @Test
    void aNegativeSampleIsRefused() {
        assertThatThrownBy(() -> new LatencyRecorder().add(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void beyondTheReservoirCountMinMaxAndMeanStayExactAndMemoryStaysBounded() {
        LatencyRecorder r = new LatencyRecorder(1_000, 7);
        long sum = 0;
        for (int i = 1; i <= 50_000; i++) {
            r.add(i);
            sum += i;
        }
        LatencyStats s = r.snapshot();
        assertThat(s.count()).isEqualTo(50_000);
        assertThat(s.min()).isEqualTo(1);
        assertThat(s.max()).isEqualTo(50_000);
        assertThat(s.mean()).isEqualTo(sum / 50_000.0);
        assertThat(r.retained()).isEqualTo(1_000);
        // percentiles come from the sample, so they are approximate: within 8 % of the true rank on
        // a uniform stream
        assertThat((double) s.p50()).isBetween(25_000 * 0.92, 25_000 * 1.08);
        assertThat((double) s.p95()).isBetween(47_500 * 0.95, 47_500 * 1.05);
    }

    @Test
    void theSameSeedGivesTheSameResultAndADifferentSeedMayDiffer() {
        LatencyStats a = fill(new LatencyRecorder(500, 42));
        LatencyStats b = fill(new LatencyRecorder(500, 42));
        LatencyStats c = fill(new LatencyRecorder(500, 43));
        assertThat(a).isEqualTo(b);
        assertThat(a.count()).isEqualTo(c.count());
    }

    private static LatencyStats fill(LatencyRecorder r) {
        Random rnd = new Random(1);
        for (int i = 0; i < 5_000; i++) r.add(rnd.nextInt(10_000));
        return r.snapshot();
    }

    @Test
    void exactUpToTheCapacity() {
        LatencyRecorder r = new LatencyRecorder(1_000, 1);
        List<Long> all = new ArrayList<>();
        Random rnd = new Random(5);
        for (int i = 0; i < 1_000; i++) {
            long v = rnd.nextInt(100_000);
            all.add(v);
            r.add(v);
        }
        Collections.sort(all);
        LatencyStats s = r.snapshot();
        assertThat(s.p50()).isEqualTo(all.get(499));
        assertThat(s.p95()).isEqualTo(all.get(949));
        assertThat(s.p99()).isEqualTo(all.get(989));
    }

    @Test
    void theCapacityMustBePositive() {
        assertThatThrownBy(() -> new LatencyRecorder(0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }

    @Test
    void aSnapshotIsNotAffectedByLaterSamples() {
        LatencyRecorder r = new LatencyRecorder();
        r.add(10);
        LatencyStats before = r.snapshot();
        r.add(1_000);
        assertThat(before.count()).isEqualTo(1);
        assertThat(r.snapshot().count()).isEqualTo(2);
    }
}
