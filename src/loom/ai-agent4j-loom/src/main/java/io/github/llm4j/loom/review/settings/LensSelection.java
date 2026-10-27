package io.github.llm4j.loom.review.settings;

import io.github.llm4j.loom.autonomy.expr.Suggest;
import io.github.llm4j.loom.review.model.Lens;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Which lenses run. The grammar, used by {@code --lens} and by {@code lenses =} in {@code
 * review.properties}, is a comma-separated list:
 *
 * <ul>
 *   <li>{@code all}: every lens, optional ones too;
 *   <li>{@code +name} / {@code -name}: add or remove one lens from the starting set;
 *   <li>plain names ({@code security,cost}): exactly those lenses.
 * </ul>
 *
 * Plain names and {@code +}/{@code -} are not mixed, so what a line means is never a guess.
 */
public final class LensSelection {

    private LensSelection() {}

    /** The lenses on when nothing is said: every lens that is not optional. */
    public static Set<Lens> defaults() {
        Set<Lens> on = EnumSet.noneOf(Lens.class);
        for (Lens l : Lens.values()) if (!l.optional()) on.add(l);
        return on;
    }

    /**
     * Applies {@code spec} to {@code base}.
     *
     * @throws IllegalArgumentException with a message naming the nearest valid lens when a word is
     *     not a lens, or when the two styles are mixed
     */
    public static Set<Lens> resolve(String spec, Set<Lens> base) {
        Set<Lens> result = EnumSet.noneOf(Lens.class);
        result.addAll(base);
        List<String> tokens = new ArrayList<>();
        for (String t : spec.split(",")) if (!t.isBlank()) tokens.add(t.trim());
        if (tokens.isEmpty()) throw new IllegalArgumentException("a lens list is empty");
        boolean relative = tokens.stream().anyMatch(t -> t.startsWith("+") || t.startsWith("-"));
        boolean absolute = tokens.stream().anyMatch(t -> !t.startsWith("+") && !t.startsWith("-"));
        if (relative && absolute && !tokens.contains("all"))
            throw new IllegalArgumentException(
                    "mix of plain and +/- lens names in '" + spec + "': use one style");
        if (!relative) {
            Set<Lens> exact = EnumSet.noneOf(Lens.class);
            for (String t : tokens) {
                if (t.equals("all")) exact.addAll(Arrays.asList(Lens.values()));
                else exact.add(lens(t));
            }
            return exact;
        }
        for (String t : tokens) {
            char sign = t.charAt(0);
            if (sign == '+') result.add(lens(t.substring(1)));
            else result.remove(lens(t.substring(1)));
        }
        return result;
    }

    private static Lens lens(String word) {
        return Lens.parse(word)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "'"
                                                + word
                                                + "' is not a lens"
                                                + Suggest.hint(
                                                        word,
                                                        Arrays.stream(Lens.values())
                                                                .map(Lens::id)
                                                                .toList())));
    }
}
