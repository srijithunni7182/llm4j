package io.github.llm4j.loom.eval;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Metamorphic cases: instead of asserting what the output is, assert a relation between two runs,
 * so no ground truth is needed. An irrelevant addition, a reordering of what the input says and a
 * paraphrase must not change the conclusion, so the derived case keeps the original's
 * expectations. Removing the decisive evidence (monotonicity) or flipping it (negation) must
 * change it, so the derived case forbids the original conclusion. A pair that passed alone and
 * fails perturbed exposes a system that matches surface features instead of reasoning, which
 * accuracy alone does not show.
 *
 * <p>Nothing here calls a model. Irrelevant and order are mechanical. Monotonicity and negation
 * need the domain's evidence, which the expert marks on the case with {@code decisive:<text>} or
 * {@code negate:<from>=><to>}. Paraphrase needs reworded inputs, which a person or a model supplies
 * in a file; those cases are tagged {@code source:synthetic}.
 */
public final class Metamorphic {

    /** The tag that marks a case derived here, so it is not derived again. */
    public static final String DERIVED_TAG = "derived:metamorphic";

    /** A neutral sentence with nothing to do with any task; the expert may replace it. */
    static final String IRRELEVANT = "By the way, the weather has been mild this week.";

    private static final String RELATION = "relation:";
    private static final String PAIR = "pair:";

    private Metamorphic() {}

    private static final String DECISIVE = "decisive:";
    private static final String NEGATE = "negate:";
    private static final String NEGATE_ARROW = "=>";
    private static final String SYNTHETIC_TAG = "source:synthetic";

    /** The relations that can be derived; none of them calls a model. */
    public enum Relation {
        IRRELEVANT,
        ORDER,
        PARAPHRASE,
        MONOTONICITY,
        NEGATION;

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
                Relation found = null;
                for (Relation r : values()) if (r.word().equals(name)) found = r;
                if (found == null)
                    throw new IllegalArgumentException("unknown relation " + name + "; the relations are irrelevant, order, paraphrase, monotonicity, negation");
                out.add(found);
            }
            if (out.isEmpty()) throw new IllegalArgumentException("name at least one relation: irrelevant, order, paraphrase, monotonicity, negation");
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
        return derive(originals, relations, Map.of());
    }

    /**
     * As {@link #derive(List, List)}, with reworded inputs for the paraphrase relation.
     *
     * @param paraphrases the reworded input of a case, by the case's id
     */
    public static Derived derive(List<EvalScenario> originals, List<Relation> relations, Map<String, String> paraphrases) {
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
                EvalScenario derived = switch (r) {
                    case IRRELEVANT -> same(s, id, s.input().strip() + " " + IRRELEVANT, r, List.of());
                    case ORDER -> {
                        String input = reorder(s.input());
                        if (input == null) skipped.add(id + ": " + r.word() + " skipped, the input is one sentence and there is nothing to reorder");
                        yield input == null ? null : same(s, id, input, r, List.of());
                    }
                    case PARAPHRASE -> {
                        String input = paraphrases.get(id);
                        if (input == null || input.isBlank()) skipped.add(id + ": " + r.word() + " skipped, no paraphrase was supplied for it");
                        yield input == null || input.isBlank() ? null : same(s, id, input.strip(), r, List.of(SYNTHETIC_TAG));
                    }
                    case MONOTONICITY, NEGATION -> changed(s, id, r, skipped);
                };
                if (derived != null) out.add(derived);
            }
        }
        return new Derived(out, skipped);
    }

    /** The original's expectations, kept: the conclusion must not change. */
    private static EvalScenario same(EvalScenario s, String id, String input, Relation r, List<String> extraTags) {
        return copy(s, id + "~" + r.word(), input, r, id, extraTags, s.correctConclusion(), s.acceptedEquivalents(), s.rejected(), true);
    }

    /**
     * The evidence taken away or flipped: the original conclusion (and what counted as it) becomes
     * a rejected answer, so a system that still gives it did not use the evidence. What the
     * original required of its answer (contains, is, tools, rubric, expect, citations, acceptable
     * actions) described the old conclusion and is dropped; what it forbade is kept. With no
     * positive oracle such a case can fail or be unjudged, never pass: a changed answer is for a
     * person to read.
     */
    private static EvalScenario changed(EvalScenario s, String id, Relation r, List<String> skipped) {
        String input = s.input();
        String changed;
        if (r == Relation.MONOTONICITY) {
            String text = tagValue(s, DECISIVE);
            if (text == null) {
                skipped.add(id + ": monotonicity skipped, no decisive:<text> tag names the evidence to remove");
                return null;
            }
            if (!input.contains(text)) {
                skipped.add(id + ": monotonicity skipped, the decisive text does not occur in the input");
                return null;
            }
            changed = input.replace(text, "").replaceAll("\\s{2,}", " ").strip();
        } else {
            String rule = tagValue(s, NEGATE);
            int arrow = rule == null ? -1 : rule.indexOf(NEGATE_ARROW);
            if (arrow < 0) {
                skipped.add(id + ": negation skipped, no negate:<from>=><to> tag says how to flip the evidence");
                return null;
            }
            String from = rule.substring(0, arrow);
            if (!input.contains(from)) {
                skipped.add(id + ": negation skipped, the text to flip does not occur in the input");
                return null;
            }
            changed = input.replace(from, rule.substring(arrow + NEGATE_ARROW.length()));
        }
        List<String> forbidden = new ArrayList<>();
        if (s.correctConclusion() != null) forbidden.add(s.correctConclusion());
        forbidden.addAll(s.equivalents());
        if (forbidden.isEmpty()) {
            skipped.add(id + ": " + r.word() + " skipped, the case has no correct_conclusion to forbid once the evidence is changed");
            return null;
        }
        List<String> rejected = new ArrayList<>(s.rejectedAnswers());
        rejected.addAll(forbidden);
        return copy(s, id + "~" + r.word(), changed, r, id, List.of(), null, null, rejected, false);
    }

    private static String tagValue(EvalScenario s, String prefix) {
        if (s.tags() == null) return null;
        return s.tags().stream().filter(t -> t != null && t.startsWith(prefix)).map(t -> t.substring(prefix.length())).findFirst().orElse(null);
    }

    private static String reorder(String input) {
        String[] sentences = input.strip().split("(?<=[.!?])\\s+");
        if (sentences.length < 2) return null;
        List<String> reversed = new ArrayList<>(List.of(sentences));
        java.util.Collections.reverse(reversed);
        return String.join(" ", reversed);
    }

    private static EvalScenario copy(
            EvalScenario s,
            String id,
            String input,
            Relation r,
            String pair,
            List<String> extraTags,
            String conclusion,
            List<String> equivalents,
            List<String> rejected,
            boolean keepPositive) {
        List<String> tags = new ArrayList<>(s.tags() == null ? List.of() : s.tags());
        tags.removeIf(t -> t.startsWith(RELATION) || t.startsWith(PAIR) || t.startsWith("split:heldout"));
        if (s.tags() != null && s.tags().contains("split:heldout")) tags.add("split:heldout");
        tags.add(RELATION + r.word());
        tags.add(PAIR + pair);
        tags.add(DERIVED_TAG);
        tags.addAll(extraTags);
        return new EvalScenario(
                id,
                input,
                keepPositive ? s.expectedOutputContains() : null,
                keepPositive ? s.expectedOutput() : null,
                keepPositive ? s.expectedTools() : null,
                s.context(),
                s.retrievalContext(),
                id,
                s.dimensions(),
                tags,
                keepPositive ? s.rubric() : null,
                keepPositive ? s.expect() : null,
                s.inputs(),
                s.expectedOutputNotContains(),
                s.expectedMinWords(),
                s.expectedMaxWords(),
                conclusion,
                equivalents,
                rejected,
                keepPositive ? s.acceptableActions() : null,
                s.unacceptableActions(),
                keepPositive ? s.requiredCitations() : null);
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
        return deriveFolder(from, out, relations, Map.of());
    }

    /** As {@link #deriveFolder(java.nio.file.Path, java.nio.file.Path, List)}, with reworded inputs by case id. */
    public static Written deriveFolder(java.nio.file.Path from, java.nio.file.Path out, List<Relation> relations, Map<String, String> paraphrases)
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
            Derived d = derive(e.getValue(), relations, paraphrases);
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
            if (!originalPassed) continue;
            if (relation.equals("monotonicity") || relation.equals("negation"))
                out.add("case " + pair + " did not change its conclusion when its decisive evidence was changed by the relation " + relation + " (" + d.label() + "): the system may be ignoring the evidence.");
            else
                out.add("case " + pair + " passes alone and fails when its input is changed by the relation " + relation + " (" + d.label() + "): the system may be matching surface features rather than reasoning.");
        }
        return out;
    }

    private static String tag(ScenarioResult r, String prefix) {
        for (String t : r.tags()) if (t != null && t.startsWith(prefix)) return t.substring(prefix.length());
        return null;
    }
}
