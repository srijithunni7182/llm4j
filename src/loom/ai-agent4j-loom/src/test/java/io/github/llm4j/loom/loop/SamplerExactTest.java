package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R20 of spec 14, exactly: the numbers a sample is built from, and the order it comes back in. */
class SamplerExactTest {

    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

    private static RunRecord run(String id, int seconds) {
        return new RunRecord(
                id,
                Path.of("/x/" + id),
                Path.of("/x/main.loom"),
                "Main",
                Map.of(),
                "h",
                BASE.plusSeconds(seconds),
                Map.of());
    }

    private static List<RunRecord> runs(String prefix, int n, int offset) {
        List<RunRecord> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(run(prefix + i, offset + i));
        return out;
    }

    private static List<String> ids(Sampler.Sample s) {
        return s.chosen().stream().map(RunRecord::id).toList();
    }

    @Test
    void sharesFollowTheSizesOfTheStrata() {
        Map<String, List<RunRecord>> by = new LinkedHashMap<>();
        by.put("a", runs("a", 10, 0));
        by.put("b", runs("b", 30, 100));
        assertThat(Sampler.allocate(by, 8)).containsEntry("a", 2).containsEntry("b", 6);
        assertThat(Sampler.allocate(by, 20)).containsEntry("a", 5).containsEntry("b", 15);
    }

    @Test
    void whenRoundingGivesTooFewTheStrataWithMostLeftGetTheRest() {
        Map<String, List<RunRecord>> by = new LinkedHashMap<>();
        by.put("a", runs("a", 5, 0));
        by.put("b", runs("b", 5, 100));
        by.put("c", runs("c", 5, 200));
        Map<String, Integer> take = Sampler.allocate(by, 10);
        assertThat(take.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(10);
        assertThat(take.values()).allMatch(v -> v == 3 || v == 4);
    }

    @Test
    void whenTheStrataAreSmallerThanTheLimitEachGivesAllItHas() {
        Map<String, List<RunRecord>> by = new LinkedHashMap<>();
        by.put("a", runs("a", 1, 0));
        by.put("b", runs("b", 2, 100));
        assertThat(Sampler.allocate(by, 50)).containsEntry("a", 1).containsEntry("b", 2);
    }

    @Test
    void whenOneEachOvershootsTheLargestGiversGiveBack() {
        Map<String, List<RunRecord>> by = new LinkedHashMap<>();
        by.put("rare1", runs("p", 1, 0));
        by.put("rare2", runs("q", 1, 10));
        by.put("big", runs("b", 98, 100));
        Map<String, Integer> take = Sampler.allocate(by, 10);
        assertThat(take)
                .containsEntry("rare1", 1)
                .containsEntry("rare2", 1)
                .containsEntry("big", 8);
    }

    @Test
    void aLimitOfExactlyThePopulationOrLessTakesEverythingAndOneLessTakesOneLess() {
        List<RunRecord> all = runs("r", 6, 0);
        for (int limit : new int[] {0, -1, 6, 7})
            assertThat(
                            Sampler.choose(
                                            all,
                                            Sampler.Strategy.RANDOM,
                                            limit,
                                            1,
                                            r -> "x",
                                            r -> false)
                                    .chosen())
                    .hasSize(6);
        assertThat(
                        Sampler.choose(all, Sampler.Strategy.RANDOM, 5, 1, r -> "x", r -> false)
                                .chosen())
                .hasSize(5);
    }

    @Test
    void whateverTheStrategyTheSampleComesBackNewestFirst() {
        List<RunRecord> all = runs("r", 50, 0);
        for (Sampler.Strategy s : Sampler.Strategy.values()) {
            List<String> ids = ids(Sampler.choose(all, s, 10, 3, r -> "x", r -> false));
            List<Integer> n = ids.stream().map(i -> Integer.parseInt(i.substring(1))).toList();
            assertThat(n).as(s.name()).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        }
    }

    @Test
    void aRandomSampleIsNotJustTheFirstOnesAndASeedRepeats() {
        List<RunRecord> all = runs("r", 200, 0);
        Sampler.Sample a =
                Sampler.choose(all, Sampler.Strategy.RANDOM, 20, 1, r -> "x", r -> false);
        Sampler.Sample again =
                Sampler.choose(all, Sampler.Strategy.RANDOM, 20, 1, r -> "x", r -> false);
        Sampler.Sample recent =
                Sampler.choose(all, Sampler.Strategy.RECENT, 20, 1, r -> "x", r -> false);
        assertThat(ids(a)).isEqualTo(ids(again)).isNotEqualTo(ids(recent));
    }

    @Test
    void aStratifiedSampleDependsOnTheSeedAndRepeatsWithIt() {
        List<RunRecord> all = runs("r", 200, 0);
        java.util.function.Function<RunRecord, String> by =
                r -> Integer.parseInt(r.id().substring(1)) % 2 == 0 ? "even" : "odd";
        Sampler.Sample a = Sampler.choose(all, Sampler.Strategy.STRATIFIED, 20, 1, by, r -> false);
        Sampler.Sample again =
                Sampler.choose(all, Sampler.Strategy.STRATIFIED, 20, 1, by, r -> false);
        Sampler.Sample other =
                Sampler.choose(all, Sampler.Strategy.STRATIFIED, 20, 2, by, r -> false);
        assertThat(ids(a)).isEqualTo(ids(again)).isNotEqualTo(ids(other));
        assertThat(a.strata().get("even")).containsExactly(100, 10);
        assertThat(a.strata().get("odd")).containsExactly(100, 10);
    }

    @Test
    void aSampleOfEverythingCountsEachStratumAsFullyChosen() {
        List<RunRecord> all = runs("r", 6, 0);
        Sampler.Sample s =
                Sampler.choose(
                        all,
                        Sampler.Strategy.STRATIFIED,
                        6,
                        1,
                        r -> r.id().equals("r0") ? "a" : "b",
                        r -> false);
        assertThat(s.strata().get("a")).containsExactly(1, 1);
        assertThat(s.strata().get("b")).containsExactly(5, 5);
        assertThat(s.isRandom()).isTrue();
    }

    @Test
    void theStrategyIsNamedByItsLabel() {
        assertThat(Sampler.Strategy.FAILURES_FIRST.label()).isEqualTo("failures-first");
        assertThat(Sampler.Strategy.of("stratified")).isEqualTo(Sampler.Strategy.STRATIFIED);
    }
}
