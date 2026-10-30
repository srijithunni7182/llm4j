package io.github.llm4j.loom.eval;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Metamorphic cases: instead of asserting what the output is, assert a relation between two runs,
 * so no ground truth is needed. Two relations are mechanical and need no model: an irrelevant
 * addition and a reordering of what the input says must not change the conclusion, so the derived
 * case keeps the original's expectations. A pair that passed alone and fails perturbed exposes a
 * system that matches surface features instead of reasoning, which accuracy alone does not show.
 *
 * <p>Paraphrase invariance needs a model to reword; monotonicity and negation sensitivity need the
 * domain's decisive evidence. Those are written by hand, as two cases tagged {@code pair:<id>}.
 */
public final class Metamorphic {

    /** The tag that marks a case derived here, so it is not derived again. */
    public static final String DERIVED_TAG = "derived:metamorphic";

    /** A neutral sentence with nothing to do with any task; the expert may replace it. */
    static final String IRRELEVANT = "By the way, the weather has been mild this week.";

    private static final String RELATION = "relation:";
    private static final String PAIR = "pair:";

    private Metamorphic() {}

    /** The relations that can be derived without a model. */
    public enum Relation {
        IRRELEVANT,
        ORDER;

        /** The name used on the command line. */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Reads names separated by commas. */
        public static List<Relation> parse(String names) {
            List<Relation> out = new ArrayList<>();
            for (String raw : names.split("\\s*,\\s*")) {
                String name = raw.strip().toLowerCase(Locale.ROOT);
                if (name.isEmpty()) continue;
                if (List.of("paraphrase", "monotonicity", "negation").contains(name))
                    throw new IllegalArgumentException(
                            name
                                    + " needs a model or the domain's evidence and is not derived here: write the pair by hand (two cases tagged pair:<id>). The relations derived here are irrelevant, order");
                Relation found = null;
                for (Relation r : values()) if (r.word().equals(name)) found = r;
                if (found == null)
                    throw new IllegalArgumentException("unknown relation " + name + "; the relations derived here are irrelevant, order");
                out.add(found);
            }
            if (out.isEmpty()) throw new IllegalArgumentException("name at least one relation: irrelevant, order");
            return out;
        }
    }

    /**
     * What was derived.
     *
     * @param cases the new cases
     * @param skipped one line for each case and relation that could not be derived, and why
     */
    public record Derived(List<EvalScenario> cases, List<String> skipped) {}

    /** Derives the cases; every original is kept as it is by the caller. */
    public static Derived derive(List<EvalScenario> originals, List<Relation> relations) {
        List<EvalScenario> out = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (EvalScenario s : originals) {
            if (s.tags() != null && s.tags().contains(DERIVED_TAG)) continue;
            String id = s.id() != null ? s.id() : s.name();
            for (Relation r : relations) {
                if (s.input() == null || s.input().isBlank()) {
                    skipped.add(id + ": " + r.word() + " skipped, the case has no input to change");
                    continue;
                }
                String input = r == Relation.IRRELEVANT ? s.input().strip() + " " + IRRELEVANT : reorder(s.input());
                if (input == null) {
                    skipped.add(id + ": " + r.word() + " skipped, the input is one sentence and there is nothing to reorder");
                    continue;
                }
                out.add(copy(s, id + "~" + r.word(), input, r, id));
            }
        }
        return new Derived(out, skipped);
    }

    private static String reorder(String input) {
        String[] sentences = input.strip().split("(?<=[.!?])\\s+");
        if (sentences.length < 2) return null;
        List<String> reversed = new ArrayList<>(List.of(sentences));
        java.util.Collections.reverse(reversed);
        return String.join(" ", reversed);
    }

    private static EvalScenario copy(EvalScenario s, String id, String input, Relation r, String pair) {
        List<String> tags = new ArrayList<>(s.tags() == null ? List.of() : s.tags());
        tags.removeIf(t -> t.startsWith(RELATION) || t.startsWith(PAIR) || t.startsWith("split:heldout"));
        if (s.tags() != null && s.tags().contains("split:heldout")) tags.add("split:heldout");
        tags.add(RELATION + r.word());
        tags.add(PAIR + pair);
        tags.add(DERIVED_TAG);
        return new EvalScenario(
                id,
                input,
                s.expectedOutputContains(),
                s.expectedOutput(),
                s.expectedTools(),
                s.context(),
                s.retrievalContext(),
                id,
                s.dimensions(),
                tags,
                s.rubric(),
                s.expect(),
                s.inputs(),
                s.expectedOutputNotContains(),
                s.expectedMinWords(),
                s.expectedMaxWords(),
                s.correctConclusion(),
                s.acceptedEquivalents(),
                s.rejected(),
                s.acceptableActions(),
                s.unacceptableActions(),
                s.requiredCitations());
    }

    /** What {@link #deriveFolder} did: how many cases it derived and what it skipped. */
    public record Written(int derived, List<String> skipped) {}

    /**
     * Reads a dataset folder and writes it to {@code out} with the derived cases added to each file.
     * The folder {@code out} must be new or empty, so nothing is overwritten.
     *
     * @throws java.io.IOException when it cannot read or write
     * @throws IllegalArgumentException when {@code out} is not empty
     */
    public static Written deriveFolder(java.nio.file.Path from, java.nio.file.Path out, List<Relation> relations)
            throws java.io.IOException {
        if (java.nio.file.Files.isDirectory(out))
            try (var existing = java.nio.file.Files.list(out)) {
                if (existing.findAny().isPresent())
                    throw new IllegalArgumentException(out + " is not empty; name a new folder so nothing is overwritten");
            }
        java.nio.file.Files.createDirectories(out);
        int derived = 0;
        List<String> skipped = new ArrayList<>();
        for (var e : io.github.llm4j.eval.dataset.EvalScenarios.fromDirectory(from).entrySet()) {
            Derived d = derive(e.getValue(), relations);
            List<EvalScenario> all = new ArrayList<>(e.getValue());
            all.addAll(d.cases());
            derived += d.cases().size();
            skipped.addAll(d.skipped());
            io.github.llm4j.eval.dataset.EvalScenarios.toYaml(all, out.resolve(e.getKey() + ".yaml"));
        }
        return new Written(derived, skipped);
    }

    /**
     * The pairs that passed alone and fail perturbed: one line each, naming the original and the
     * relation. A pair where the original did not pass says nothing about the relation.
     */
    public static List<String> fragile(List<ScenarioResult> results) {
        List<String> out = new ArrayList<>();
        for (ScenarioResult d : results) {
            String pair = tag(d, PAIR);
            String relation = tag(d, RELATION);
            if (pair == null || relation == null || d.status() != Status.FAIL) continue;
            boolean originalPassed =
                    results.stream().anyMatch(o -> pair.equals(o.id()) && o.status() == Status.PASS);
            if (originalPassed)
                out.add("case " + pair + " passes alone and fails when its input is changed by the relation " + relation + " (" + d.label() + "): the system may be matching surface features rather than reasoning.");
        }
        return out;
    }

    private static String tag(ScenarioResult r, String prefix) {
        for (String t : r.tags()) if (t != null && t.startsWith(prefix)) return t.substring(prefix.length());
        return null;
    }
}
