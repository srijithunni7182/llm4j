package io.github.llm4j.loom.loop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Chooses which past runs to compare. Deterministic for a seed. The sample says what it is: the
 * population it came from, the strategy, the seed and the sizes of the strata, so a report can
 * never claim more than the sample supports.
 */
public final class Sampler {

    private Sampler() {}

    public enum Strategy {
        RANDOM,
        RECENT,
        STRATIFIED,
        FAILURES_FIRST;

        public static Strategy of(String name) {
            return switch (name) {
                case "random" -> RANDOM;
                case "recent" -> RECENT;
                case "stratified" -> STRATIFIED;
                case "failures-first" -> FAILURES_FIRST;
                default -> throw new IllegalArgumentException(
                        "--sample takes random, recent, stratified or failures-first, not "
                                + name
                                + ".");
            };
        }

        public String label() {
            return name().toLowerCase().replace('_', '-');
        }
    }

    /**
     * @param chosen the runs to compare, in a fixed order
     * @param population how many runs there were to choose from
     * @param strategy how they were chosen
     * @param seed the seed (used by random and stratified)
     * @param strata for a stratified sample: each stratum's size in the population and in the
     *     sample
     */
    public record Sample(
            List<RunRecord> chosen,
            int population,
            Strategy strategy,
            long seed,
            Map<String, int[]> strata) {

        /**
         * True only when every run had the same chance: the one case where a share may be stated.
         */
        public boolean isRandom() {
            return strategy == Strategy.RANDOM || chosen.size() == population;
        }
    }

    public static Sample choose(
            List<RunRecord> population,
            Strategy strategy,
            int limit,
            long seed,
            Function<RunRecord, String> stratum,
            Function<RunRecord, Boolean> failed) {
        List<RunRecord> all = new ArrayList<>(population);
        all.sort(Comparator.comparing(RunRecord::modified).reversed().thenComparing(RunRecord::id));
        if (limit <= 0 || limit >= all.size())
            return new Sample(all, all.size(), strategy, seed, strata(all, all, stratum));
        List<RunRecord> chosen;
        switch (strategy) {
            case RECENT -> chosen = new ArrayList<>(all.subList(0, limit));
            case RANDOM -> {
                List<RunRecord> shuffled = new ArrayList<>(all);
                java.util.Collections.shuffle(shuffled, new Random(seed));
                chosen = new ArrayList<>(shuffled.subList(0, limit));
            }
            case FAILURES_FIRST -> {
                chosen = new ArrayList<>();
                for (RunRecord r : all) if (chosen.size() < limit && failed.apply(r)) chosen.add(r);
                for (RunRecord r : all)
                    if (chosen.size() < limit && !chosen.contains(r)) chosen.add(r);
            }
            default -> chosen = stratified(all, limit, seed, stratum);
        }
        chosen.sort(
                Comparator.comparing(RunRecord::modified).reversed().thenComparing(RunRecord::id));
        return new Sample(chosen, all.size(), strategy, seed, strata(all, chosen, stratum));
    }

    /** At least one run from every stratum, the rest in proportion to the strata's sizes. */
    static List<RunRecord> stratified(
            List<RunRecord> all, int limit, long seed, Function<RunRecord, String> stratum) {
        Map<String, List<RunRecord>> by = new TreeMap<>();
        for (RunRecord r : all) by.computeIfAbsent(stratum.apply(r), k -> new ArrayList<>()).add(r);
        Map<String, Integer> take = allocate(by, limit);
        Random random = new Random(seed);
        List<RunRecord> out = new ArrayList<>();
        for (var e : by.entrySet()) {
            List<RunRecord> members = new ArrayList<>(e.getValue());
            java.util.Collections.shuffle(members, random);
            out.addAll(members.subList(0, take.get(e.getKey())));
        }
        return out;
    }

    /**
     * How many each stratum gives: one at least, the rest by size, the total exactly {@code limit}.
     */
    static Map<String, Integer> allocate(Map<String, List<RunRecord>> by, int limit) {
        int total = by.values().stream().mapToInt(List::size).sum();
        Map<String, Integer> take = new LinkedHashMap<>();
        int given = 0;
        for (var e : by.entrySet()) {
            int share = Math.max(1, (int) Math.round((double) limit * e.getValue().size() / total));
            share = Math.min(share, e.getValue().size());
            take.put(e.getKey(), share);
            given += share;
        }
        // too many (the minimum of one each overshoots): take from the largest givers
        while (given > limit) {
            String biggest =
                    take.entrySet().stream()
                            .filter(e -> e.getValue() > 1)
                            .max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey)
                            .orElse(null);
            if (biggest == null) break; // more strata than the limit: one each is as few as it gets
            take.merge(biggest, -1, Integer::sum);
            given--;
        }
        // too few: give to the strata with the most left
        while (given < limit) {
            String roomiest =
                    by.entrySet().stream()
                            .filter(e -> take.get(e.getKey()) < e.getValue().size())
                            .max(
                                    Comparator.comparingInt(
                                            e -> e.getValue().size() - take.get(e.getKey())))
                            .map(Map.Entry::getKey)
                            .orElse(null);
            if (roomiest == null) break;
            take.merge(roomiest, 1, Integer::sum);
            given++;
        }
        return take;
    }

    private static Map<String, int[]> strata(
            List<RunRecord> all, List<RunRecord> chosen, Function<RunRecord, String> stratum) {
        Map<String, int[]> out = new TreeMap<>();
        for (RunRecord r : all) out.computeIfAbsent(stratum.apply(r), k -> new int[2])[0]++;
        for (RunRecord r : chosen) out.computeIfAbsent(stratum.apply(r), k -> new int[2])[1]++;
        return out;
    }
}
