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

    /** The split a case belongs to; development when it names none (or names one that is not a split). */
    public static String of(EvalScenario s) {
        String named = named(s);
        return named != null && ALL.contains(named) ? named : DEVELOPMENT;
    }

    /** The name written after {@code split:}, even if it is not a split; null when there is no such tag. */
    public static String named(EvalScenario s) {
        if (s.tags() == null) return null;
        return s.tags().stream()
                .filter(t -> t != null && t.startsWith(PREFIX))
                .map(t -> t.substring(PREFIX.length()).strip())
                .findFirst()
                .orElse(null);
    }

    /** Whether the tags of a result name the held-out split. */
    public static boolean isHeldOut(List<String> tags) {
        return tags.contains(PREFIX + HELD_OUT);
    }
}
