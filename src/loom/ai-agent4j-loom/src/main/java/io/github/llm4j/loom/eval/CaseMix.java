package io.github.llm4j.loom.eval;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a dataset is made of, read from case tags so the dataset format does not change. The three
 * case classes the QA guide says nobody builds ({@code class:unanswerable}, {@code
 * class:obvious-wrong}, {@code class:conservative-action}) are warned about below a tenth of the
 * cases each; a reported slice ({@code domain:}, {@code difficulty:}, {@code split:}) below ten
 * cases is called an anecdote; the mix of {@code source:} tags is information only. The guide's
 * numbers are starting points the domain expert sets, so every figure here says what it is.
 */
public final class CaseMix {

    /** The case classes the guide asks for, in the order they are reported. */
    public static final List<String> CLASSES =
            List.of("unanswerable", "obvious-wrong", "conservative-action");

    /** The tag families a report slices by. */
    private static final List<String> SLICE_FAMILIES = List.of("domain", "difficulty", "split");

    private static final double CLASS_FLOOR = 0.10;
    private static final int SMALL_SLICE = 10;

    private CaseMix() {}

    /** The notes {@code weave eval --check} prints about the mix, empty for no cases. */
    public static List<String> notes(List<EvalScenario> cases) {
        List<String> notes = new ArrayList<>();
        if (cases.isEmpty()) return notes;
        int total = cases.size();
        for (String kind : CLASSES) {
            long n = cases.stream().filter(s -> hasTag(s, "class:" + kind)).count();
            if (n < total * CLASS_FLOOR)
                notes.add(
                        "note: "
                                + n
                                + " of "
                                + total
                                + " cases are "
                                + kind
                                + " (tag class:"
                                + kind
                                + "); the guide asks for about a tenth each. Ask the domain expert for examples; the dataset's owner sets the real share.");
        }
        for (String family : SLICE_FAMILIES) {
            Map<String, Integer> counts = new TreeMap<>();
            for (EvalScenario s : cases)
                for (String tag : tags(s))
                    if (tag.startsWith(family + ":")) counts.merge(tag, 1, Integer::sum);
            counts.forEach(
                    (tag, n) -> {
                        if (n < SMALL_SLICE)
                            notes.add(
                                    "note: slice "
                                            + tag
                                            + " has "
                                            + n
                                            + " cases (fewer than "
                                            + SMALL_SLICE
                                            + "): read its rate as an anecdote, not a rate.");
                    });
        }
        Map<String, Integer> sources = new LinkedHashMap<>();
        int said = 0;
        for (EvalScenario s : cases) {
            String source =
                    tags(s).stream()
                            .filter(t -> t.startsWith("source:"))
                            .map(t -> t.substring("source:".length()))
                            .findFirst()
                            .orElse(null);
            if (source != null) {
                sources.merge(source, 1, Integer::sum);
                said++;
            }
        }
        if (said > 0) {
            List<String> parts = new ArrayList<>();
            new TreeMap<>(sources).forEach((k, v) -> parts.add(k + " " + percent(v, total)));
            if (said < total) parts.add("not said " + percent(total - said, total));
            notes.add("source mix (information, not a rule): " + String.join(", ", parts) + ".");
        }
        return notes;
    }

    private static String percent(int part, int total) {
        return Math.round(100.0 * part / total) + "%";
    }

    private static List<String> tags(EvalScenario s) {
        return s.tags() == null ? List.of() : s.tags();
    }

    private static boolean hasTag(EvalScenario s, String tag) {
        return tags(s).contains(tag);
    }
}
