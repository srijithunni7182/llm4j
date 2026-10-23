package io.github.llm4j.loom.review.fix;

import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.FixSafety;
import io.github.llm4j.loom.review.model.Severity;
import io.github.llm4j.loom.review.model.TextEdit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Applies fixes to a script's text, safely. Nothing is written by this class: it works on text and
 * returns the new text and what it did, and the caller writes the file only if the whole operation
 * went through, so it is atomic.
 *
 * <p>Each pass reviews the current text, takes the selected findings that have a fix (the most
 * serious first), keeps the ones whose edits do not overlap, applies them and reviews the result.
 * The result is kept only if it parses, adds no finding at the level of an error or of high
 * severity that was not there before, and is accepted by the reviewer; otherwise the fixes are
 * tried one at a time and those that fail are withheld with the reason. It then reviews again,
 * because one fix can make another apply or disappear, up to five passes. Running it on its own
 * output changes nothing.
 */
public final class FixApplier {

    /** Reviews a script given as text. May throw when the text does not load. */
    @FunctionalInterface
    public interface Reviewer {
        ReviewReport review(String text);
    }

    /** One fix that was applied. */
    public record Applied(String rule, int line, String label, int pass) {}

    /**
     * The outcome.
     *
     * @param text the script after the fixes
     * @param applied what was applied, in order
     * @param withheld fixes that were not applied, and why
     * @param passes how many review passes ran
     */
    public record Result(String text, List<Applied> applied, List<String> withheld, int passes) {
        public boolean changed(String original) {
            return !text.equals(original);
        }
    }

    public static final int MAX_PASSES = 5;

    private FixApplier() {}

    public static Result run(String original, Predicate<Finding> selected, Reviewer reviewer) {
        String text = original;
        List<Applied> applied = new ArrayList<>();
        List<String> withheld = new ArrayList<>();
        Set<String> tried = new HashSet<>();
        int passes = 0;
        while (passes < MAX_PASSES) {
            ReviewReport before = reviewer.review(text);
            List<Finding> candidates = candidates(before, selected, tried);
            if (candidates.isEmpty()) break;
            passes++;
            Set<String> highBefore = highs(before);
            List<Finding> chosen = nonOverlapping(candidates);
            String next = apply(text, chosen);
            Verdict v = verify(next, highBefore, reviewer);
            if (v.ok()) {
                for (Finding f : chosen) applied.add(applied(f, passes));
                text = next;
                continue;
            }
            // together they fail: one at a time, keeping those that hold
            boolean progress = false;
            for (Finding f : chosen) {
                String one = apply(text, List.of(f));
                Verdict each = verify(one, highs(reviewer.review(text)), reviewer);
                if (each.ok()) {
                    applied.add(applied(f, passes));
                    text = one;
                    progress = true;
                    // the line numbers have moved, so the rest are found again on the next pass
                    break;
                }
                tried.add(f.fingerprint());
                withheld.add(
                        f.rule()
                                + " at line "
                                + f.where().line()
                                + ": fix withheld, "
                                + each.why());
            }
            if (!progress) break;
        }
        return new Result(text, applied, withheld, passes);
    }

    private static Applied applied(Finding f, int pass) {
        return new Applied(f.rule(), f.where().line(), f.fix().orElseThrow().label(), pass);
    }

    private static List<Finding> candidates(
            ReviewReport report, Predicate<Finding> selected, Set<String> tried) {
        List<Finding> out = new ArrayList<>();
        for (Finding f : report.findings())
            if (f.fix().isPresent()
                    && selected.test(f)
                    && !tried.contains(f.fingerprint())
                    && (f.fix().get().safety() == FixSafety.SAFE || selected.test(f))) out.add(f);
        out.sort(
                Comparator.comparing(Finding::severity)
                        .thenComparingInt((Finding f) -> f.where().line())
                        .thenComparing(Finding::rule));
        return out;
    }

    /**
     * The most serious fixes first, each kept only if it does not touch what an earlier one does.
     */
    private static List<Finding> nonOverlapping(List<Finding> candidates) {
        List<Finding> chosen = new ArrayList<>();
        List<TextEdit> edits = new ArrayList<>();
        for (Finding f : candidates) {
            List<TextEdit> mine = f.fix().orElseThrow().edits();
            List<TextEdit> both = new ArrayList<>(edits);
            both.addAll(mine);
            if (!SourceText.disjoint(both) || touchesSameLine(edits, mine)) continue;
            chosen.add(f);
            edits = both;
        }
        return chosen;
    }

    private static boolean touchesSameLine(List<TextEdit> a, List<TextEdit> b) {
        for (TextEdit x : a) for (TextEdit y : b) if (x.startLine() == y.startLine()) return true;
        return false;
    }

    private static String apply(String text, List<Finding> fixes) {
        List<TextEdit> edits = new ArrayList<>();
        for (Finding f : fixes) edits.addAll(f.fix().orElseThrow().edits());
        return SourceText.of(text).apply(edits);
    }

    private record Verdict(boolean ok, String why) {}

    private static Verdict verify(String candidate, Set<String> highBefore, Reviewer reviewer) {
        ReviewReport after;
        try {
            after = reviewer.review(candidate);
        } catch (RuntimeException e) {
            return new Verdict(false, "the fixed script would not load: " + e.getMessage());
        }
        for (Finding f : after.findings())
            if (f.severity() == Severity.HIGH && !highBefore.contains(f.fingerprint()))
                return new Verdict(
                        false, "it would add a high finding: " + f.rule() + " " + f.title());
        return new Verdict(true, "");
    }

    private static Set<String> highs(ReviewReport r) {
        Set<String> out = new HashSet<>();
        for (Finding f : r.findings()) if (f.severity() == Severity.HIGH) out.add(f.fingerprint());
        return out;
    }
}
