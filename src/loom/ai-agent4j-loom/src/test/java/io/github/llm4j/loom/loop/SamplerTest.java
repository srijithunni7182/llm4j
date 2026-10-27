package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** R20 of spec 14: samples are deterministic, say what they are, and cover the rare paths. */
class SamplerTest {

    private static RunRecord run(int i, Instant at) {
        return new RunRecord(
                "r" + i,
                Path.of("/x/r" + i),
                Path.of("/x/main.loom"),
                "Main",
                Map.of(),
                "h",
                at,
                Map.of());
    }

    /** 2,000 runs: 1,800 common, 150 of a rarer kind, 50 of the rarest. */
    private static List<RunRecord> population() {
        List<RunRecord> out = new ArrayList<>();
        Instant base = Instant.parse("2026-10-01T00:00:00Z");
        for (int i = 0; i < 2000; i++) out.add(run(i, base.plusSeconds(i)));
        return out;
    }

    private static String kind(RunRecord r) {
        int i = Integer.parseInt(r.id().substring(1));
        return i % 40 == 0 ? "rarest" : i % 13 == 0 ? "rare" : "common";
    }

    @Test
    void theSameSeedGivesTheSameSampleAndAnotherSeedAnother() {
        Sampler.Sample a =
                Sampler.choose(
                        population(),
                        Sampler.Strategy.RANDOM,
                        100,
                        7,
                        SamplerTest::kind,
                        r -> false);
        Sampler.Sample b =
                Sampler.choose(
                        population(),
                        Sampler.Strategy.RANDOM,
                        100,
                        7,
                        SamplerTest::kind,
                        r -> false);
        Sampler.Sample c =
                Sampler.choose(
                        population(),
                        Sampler.Strategy.RANDOM,
                        100,
                        8,
                        SamplerTest::kind,
                        r -> false);
        assertThat(ids(a)).isEqualTo(ids(b)).isNotEqualTo(ids(c));
        assertThat(a.chosen()).hasSize(100);
        assertThat(a.population()).isEqualTo(2000);
        assertThat(a.isRandom()).isTrue();
    }

    @Test
    void aStratifiedSampleKeepsTheRarePathsInItThatARandomOneWouldDrop() {
        Sampler.Sample s =
                Sampler.choose(
                        population(),
                        Sampler.Strategy.STRATIFIED,
                        40,
                        7,
                        SamplerTest::kind,
                        r -> false);
        assertThat(s.chosen()).hasSize(40);
        Map<String, Long> by =
                s.chosen().stream()
                        .collect(Collectors.groupingBy(SamplerTest::kind, Collectors.counting()));
        assertThat(by).containsKeys("common", "rare", "rarest");
        assertThat(by.get("rarest")).isGreaterThanOrEqualTo(1);
        // the report can say each stratum's size in the population and in the sample
        assertThat(s.strata().get("rarest")[0]).isEqualTo(50);
        assertThat(s.strata().get("rarest")[1]).isEqualTo(by.get("rarest").intValue());
        assertThat(s.isRandom()).isFalse();
    }

    @Test
    void allocationIsExactlyTheLimitWhenThereIsRoomAndOneEachWhenThereAreMoreStrataThanRoom() {
        Map<String, List<RunRecord>> by = new LinkedHashMap<>();
        for (RunRecord r : population()) by.computeIfAbsent(kind(r), k -> new ArrayList<>()).add(r);
        for (int limit : new int[] {3, 10, 40, 500})
            assertThat(
                            Sampler.allocate(by, limit).values().stream()
                                    .mapToInt(Integer::intValue)
                                    .sum())
                    .as("limit " + limit)
                    .isEqualTo(limit);
        Map<String, List<RunRecord>> many = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++)
            many.put("s" + i, List.of(run(i, Instant.now()), run(i + 100, Instant.now())));
        assertThat(Sampler.allocate(many, 4).values()).allMatch(v -> v >= 1);
    }

    @Test
    void recentTakesTheNewestAndFailuresFirstTakesTheFailedThenTheNewest() {
        Sampler.Sample recent =
                Sampler.choose(
                        population(), Sampler.Strategy.RECENT, 5, 7, SamplerTest::kind, r -> false);
        assertThat(ids(recent)).containsExactly("r1999", "r1998", "r1997", "r1996", "r1995");
        Sampler.Sample failures =
                Sampler.choose(
                        population(),
                        Sampler.Strategy.FAILURES_FIRST,
                        4,
                        7,
                        SamplerTest::kind,
                        r -> r.id().equals("r3") || r.id().equals("r7"));
        assertThat(ids(failures)).contains("r3", "r7", "r1999", "r1998");
    }

    @Test
    void aLimitAtLeastThePopulationTakesEverythingAndASampleOfEverythingIsRandomEnough() {
        Sampler.Sample all =
                Sampler.choose(
                        population(),
                        Sampler.Strategy.STRATIFIED,
                        5000,
                        7,
                        SamplerTest::kind,
                        r -> false);
        assertThat(all.chosen()).hasSize(2000);
        assertThat(all.isRandom()).isTrue();
        assertThatThrownBy(() -> Sampler.Strategy.of("bogus"))
                .hasMessageContaining("--sample takes");
    }

    private static List<String> ids(Sampler.Sample s) {
        return s.chosen().stream().map(RunRecord::id).toList();
    }
}
