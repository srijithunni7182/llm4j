package io.github.llm4j.loom.eval;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.List;

/**
 * Which part of a dataset a case belongs to: {@code development} (what you tune against),
 * {@code validation} (what you check against while tuning) or {@code heldout} (sealed: it runs only
 * at a release gate). A case says so with the tag {@code split:<name>}; with none it is development.
 */
public final class Splits {
    public static final String DEVELOPMENT = "development";
    public static final String VALIDATION = "validation";
    public static final String HELD_OUT = "heldout";

    public static final List<String> ALL = List.of(DEVELOPMENT, VALIDATION, HELD_OUT);

    private static final String PREFIX = "split:";

    private Splits() {}

    /**
     * The split a case belongs to. Held-out wins over every other split tag, then validation;
     * development when it names none. A sealed case is never taken for an open one.
     */
    public static String of(EvalScenario s) {
        return of(s.tags());
    }

    /** As {@link #of(EvalScenario)}, from the tags alone (null for none). */
    public static String of(List<String> tags) {
        if (has(tags, HELD_OUT)) return HELD_OUT;
        if (has(tags, VALIDATION)) return VALIDATION;
        return DEVELOPMENT;
    }

    /**
     * The name written after {@code split:} that is not a split, else the first one; null when there
     * is no such tag. Used to report a misspelt split.
     */
    public static String named(EvalScenario s) {
        List<String> names = names(s.tags());
        return names.stream().filter(n -> !ALL.contains(n)).findFirst().orElse(names.stream().findFirst().orElse(null));
    }

    /** Whether the tags of a result name the held-out split. */
    public static boolean isHeldOut(List<String> tags) {
        return has(tags, HELD_OUT);
    }

    /** Whether any tag names this split, however it is spaced or cased. */
    public static boolean has(List<String> tags, String split) {
        return names(tags).contains(split);
    }

    /** The split names written in the tags, normalised: stripped, lower case. */
    private static List<String> names(List<String> tags) {
        if (tags == null) return List.of();
        return tags.stream()
                .filter(t -> t != null)
                .map(String::strip)
                .filter(t -> t.regionMatches(true, 0, PREFIX, 0, PREFIX.length()))
                .map(t -> t.substring(PREFIX.length()).strip().toLowerCase(java.util.Locale.ROOT))
                .toList();
    }
}
