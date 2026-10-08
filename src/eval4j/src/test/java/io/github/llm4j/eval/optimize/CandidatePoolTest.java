package io.github.llm4j.eval.optimize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class CandidatePoolTest {

    private final Candidate seed = Candidate.of("p", "seed");
    private final Candidate short1 = seed.derive("c1", "p", "a", 1);
    private final Candidate long1 = seed.derive("c2", "p", "a much longer prompt", 2);

    @Test
    void addRejectsDuplicatesByContent() {
        CandidatePool pool = new CandidatePool();

        assertThat(pool.add(seed, new double[] {0.5})).isTrue();
        assertThat(pool.add(seed.derive("c9", "p", "seed", 3), new double[] {0.9})).isFalse();

        assertThat(pool.size()).isEqualTo(1);
        assertThat(pool.contains(seed)).isTrue();
        assertThat(pool.contains(short1)).isFalse();
    }

    @Test
    void storedScoresAreCopiedDefensively() {
        CandidatePool pool = new CandidatePool();
        double[] scores = {0.5, 0.5};
        pool.add(seed, scores);
        scores[0] = 99;
        assertThat(pool.entries().get(0).validationScores()).containsExactly(0.5, 0.5);
        assertThat(pool.entries().get(0).mean()).isEqualTo(0.5);
    }

    @Test
    void bestIsHighestMeanThenShorterThenEarlier() {
        CandidatePool pool = new CandidatePool();
        pool.add(seed, new double[] {0.5, 0.5});
        pool.add(long1, new double[] {0.9, 0.9});
        pool.add(short1, new double[] {0.9, 0.9});

        assertThat(pool.best().candidate()).isEqualTo(short1); // tie on mean: shorter wins

        CandidatePool ties = new CandidatePool();
        ties.add(seed, new double[] {0.7});
        ties.add(Candidate.of("p", "seed").derive("c5", "p", "seed2", 1), new double[] {0.7});
        assertThat(ties.best().candidate()).isEqualTo(seed); // equal length: earlier wins
    }

    @Test
    void frontierAndSelectionUseParetoWeights() {
        CandidatePool pool = new CandidatePool();
        pool.add(seed, new double[] {0.2, 0.2, 0.2});
        pool.add(short1, new double[] {1.0, 0.1, 0.1});
        pool.add(long1, new double[] {0.1, 1.0, 1.0});

        assertThat(pool.frontierSize()).isEqualTo(2);
        assertThat(pool.onFrontier(short1)).isTrue();
        assertThat(pool.onFrontier(long1)).isTrue();
        assertThat(pool.onFrontier(seed)).isFalse();
        assertThat(pool.onFrontier(Candidate.of("p", "unknown"))).isFalse();

        SplittableRandom random = new SplittableRandom(1);
        int longPicks = 0;
        for (int i = 0; i < 3000; i++) {
            Candidate picked = pool.pick(random);
            assertThat(picked).isNotEqualTo(seed);
            if (picked.equals(long1)) {
                longPicks++;
            }
        }
        assertThat(longPicks / 3000.0).isBetween(2 / 3.0 - 0.04, 2 / 3.0 + 0.04);
    }

    @Test
    void singletonPoolPicksTheSeed() {
        CandidatePool pool = new CandidatePool();
        pool.add(seed, new double[] {0.3, 0.3});
        assertThat(pool.pick(new SplittableRandom(5))).isEqualTo(seed);
        assertThat(pool.frontierSize()).isEqualTo(1);
    }
}
