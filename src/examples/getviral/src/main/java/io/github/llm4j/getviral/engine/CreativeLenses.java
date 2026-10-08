package io.github.llm4j.getviral.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A deck of creative lenses — fundamentally different ways into the same idea. Each run the
 * Showrunner is dealt a few it hasn't used for this creator yet, so exploration is built into the
 * system rather than left to the model's habits (which tend to return to one favourite angle).
 */
public final class CreativeLenses {

    public static final List<String> ALL = List.of(
            "the science behind it",
            "the origin story — where it really comes from",
            "myth vs reality",
            "a behind-the-scenes insider's view",
            "the cheapest possible version (budget challenge)",
            "a 24-hour or 7-day experiment",
            "the beginner's first attempt, mistakes included",
            "an expert breaks down what amateurs miss",
            "a hot take that splits the comments",
            "nostalgia — how it used to be",
            "the future — how it will look in ten years",
            "a street-level, on-location perspective",
            "a sensory, ASMR-style close-up story",
            "the numbers game: one surprising statistic, unpacked",
            "a character-driven mini story with a twist",
            "a cultural comparison across countries",
            "the ethics and hidden costs nobody mentions",
            "a speed-run: the fastest way to do it",
            "a tier list or ranking",
            "a before-and-after transformation",
            "the one tool or ingredient that changes everything",
            "reacting to a common piece of bad advice",
            "a day-in-the-life POV",
            "a challenge the audience can join",
            "the history of one small detail",
            "an unexpected mash-up with another world",
            "a deep dive into a single moment",
            "a 'things I wish I knew' confession",
            "a debate: two sides argue it out",
            "a mini documentary with a narrator's voice");

    private CreativeLenses() { }

    /**
     * Deals {@code count} lenses, preferring ones not in {@code used}. The seed makes the deal
     * reproducible per run while still differing between runs.
     */
    public static List<String> deal(Collection<String> used, int count, long seed) {
        return dealFrom(ALL, used, count, seed);
    }

    /** Deals from any deck, skipping entries that match (or closely contain) a used one. */
    static List<String> dealFrom(List<String> deck, Collection<String> used, int count, long seed) {
        Set<String> usedNorm = used.stream().filter(u -> u != null && !u.isBlank()).map(CreativeLenses::norm)
                .collect(Collectors.toSet());
        List<String> fresh = new ArrayList<>(deck.stream().filter(l -> usedNorm.stream()
                .noneMatch(u -> u.equals(norm(l)) || u.contains(norm(l)) || norm(l).contains(u))).toList());
        List<String> pool = fresh.size() >= count ? fresh : new ArrayList<>(deck);
        java.util.Collections.shuffle(pool, new Random(seed));
        return List.copyOf(pool.subList(0, Math.min(count, pool.size())));
    }

    static String norm(String lens) {
        return lens.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip();
    }
}
