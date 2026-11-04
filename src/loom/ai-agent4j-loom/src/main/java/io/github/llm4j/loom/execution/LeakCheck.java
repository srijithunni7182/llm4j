package io.github.llm4j.loom.execution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The deterministic leak guard (docs/specs/26-policy-boundary, R9): no model, so it can run on every
 * answer and cost nothing. It finds two things.
 *
 * <ul>
 *   <li>repeated instructions: a run of at least {@code words} consecutive words that the answer
 *       shares with a text the agent must not repeat (its own prompt, the shared prompt, a
 *       persona, a policy rule), compared case-insensitively with punctuation and spacing folded;
 *   <li>internal references: a match of one of the agent's {@code internal_refs} patterns, each run
 *       under a time limit because {@code java.util.regex} has none of its own. A pattern that
 *       runs out of time counts as a match (fail closed).
 * </ul>
 */
final class LeakCheck {

    /** The shortest run of shared words that counts, unless the agent says otherwise. */
    static final int DEFAULT_WORDS = 12;

    /** The longest an {@code internal_refs} pattern may be. */
    static final int MAX_PATTERN_CHARS = 200;

    /** How long one pattern may spend on one answer. */
    static final long PATTERN_MILLIS = 200;

    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** What was found: the kind, and the size of the evidence (words or the pattern's position). */
    record Finding(String kind, int detail) {}

    private final List<String> sources;
    private final List<Pattern> refs;
    private final int words;
    private final java.util.function.LongSupplier nanoClock;

    LeakCheck(List<String> sources, List<Pattern> refs, int words, java.util.function.LongSupplier nanoClock) {
        this.sources = List.copyOf(sources);
        this.refs = List.copyOf(refs);
        this.words = Math.max(1, words);
        this.nanoClock = nanoClock;
    }

    /** The first leak in {@code answer}, or null. The text is never kept in the finding. */
    Finding find(String answer) {
        if (answer == null || answer.isBlank()) return null;
        for (int i = 0; i < refs.size(); i++) {
            if (matches(refs.get(i), answer)) return new Finding("internal_reference", i);
        }
        List<String> mine = tokens(answer);
        if (mine.size() < words) return null;
        Set<String> grams = new HashSet<>();
        for (String source : sources) {
            List<String> theirs = tokens(source);
            for (int i = 0; i + words <= theirs.size(); i++)
                grams.add(String.join(" ", theirs.subList(i, i + words)));
        }
        for (int i = 0; i + words <= mine.size(); i++) {
            if (grams.contains(String.join(" ", mine.subList(i, i + words))))
                return new Finding("repeated_instructions", words);
        }
        return null;
    }

    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        for (String t : NOT_WORD.split(text.toLowerCase(Locale.ROOT))) if (!t.isEmpty()) out.add(t);
        return out;
    }

    private boolean matches(Pattern pattern, String answer) {
        long deadline = nanoClock.getAsLong() + PATTERN_MILLIS * 1_000_000L;
        try {
            return pattern.matcher(new Deadline(answer, deadline, nanoClock)).find();
        } catch (TimedOut slow) {
            return true; // a pattern that cannot finish is treated as a match: fail closed
        }
    }

    /** Thrown from {@code charAt} when the deadline has passed. */
    static final class TimedOut extends RuntimeException {
        TimedOut() {
            super("pattern timed out", null, false, false);
        }
    }

    /** A CharSequence whose reads check a deadline, which stops a catastrophic pattern. */
    private record Deadline(String text, long deadline, java.util.function.LongSupplier nanoClock)
            implements CharSequence {
        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if (nanoClock.getAsLong() > deadline) throw new TimedOut();
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Deadline(text.substring(start, end), deadline, nanoClock);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
