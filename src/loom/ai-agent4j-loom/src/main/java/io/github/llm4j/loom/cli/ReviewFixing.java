package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.fix.FixApplier;
import io.github.llm4j.loom.review.fix.UnifiedDiff;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.FixSafety;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * {@code weave review --fix}: applies fixes to the script, each result checked by reviewing it,
 * written only if everything went through. With {@code --dry-run} it prints the diff and writes
 * nothing.
 */
final class ReviewFixing {

    private ReviewFixing() {}

    /**
     * Applies or previews the requested fixes.
     *
     * @return an exit code when the command is finished (a dry run, or an error), else null to
     *     carry on and report what remains
     */
    static Integer apply(
            ReviewCommand c, ReviewSession session, ReviewReport report, WeaveEnv env) {
        Predicate<Finding> selected;
        boolean judgement = c.fixRule != null || c.fixFinding != null;
        if (c.fixRule != null) selected = f -> f.rule().equalsIgnoreCase(c.fixRule);
        else if (c.fixFinding != null) selected = f -> f.fingerprint().equals(c.fixFinding);
        else selected = f -> f.fix().isPresent() && f.fix().get().safety() == FixSafety.SAFE;
        long matching =
                report.findings().stream()
                        .filter(f -> f.fix().isPresent() && selected.test(f))
                        .count();
        if (judgement && matching == 0) {
            env.err()
                    .println(
                            "Error: no open finding "
                                    + (c.fixRule != null
                                            ? "of rule " + c.fixRule
                                            : "with the fingerprint " + c.fixFinding)
                                    + " has a fix to apply.");
            return 2;
        }
        boolean needsYes =
                report.findings().stream()
                        .anyMatch(
                                f ->
                                        selected.test(f)
                                                && f.fix().isPresent()
                                                && f.fix().get().safety() == FixSafety.REVIEW);
        if (needsYes && !c.yes && !c.dryRun) {
            env.err()
                    .println(
                            "Error: this fix needs your judgement. Look at it with --dry-run, then repeat with --yes to apply it.");
            return 2;
        }
        Path file = c.script.toPath();
        String original;
        try {
            original = Files.readString(file);
        } catch (java.io.IOException e) {
            env.err().println("Error: " + file + " could not be read: " + e.getMessage());
            return 2;
        }
        FixApplier.Result result;
        try {
            result = FixApplier.run(original, selected, session::reviewText);
        } catch (RuntimeException e) {
            env.err().println("Error: fixes could not be checked: " + e.getMessage());
            return 2;
        }
        for (String w : result.withheld()) env.err().println("Note: " + w);
        boolean changed = result.changed(original);
        if (c.dryRun) {
            String diff = UnifiedDiff.of(c.script.getName(), original, result.text());
            env.out().print(diff.isEmpty() ? "No fixes are pending.\n" : diff);
            return changed ? 1 : 0;
        }
        if (!changed) {
            env.out().println("No fixes to apply.");
            return null;
        }
        try {
            Path tmp = file.resolveSibling("." + c.script.getName() + ".fix-" + System.nanoTime());
            Files.writeString(tmp, result.text());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            env.err().println("Error: " + file + " could not be written: " + e.getMessage());
            return 2;
        }
        env.out()
                .println(
                        "Fixed "
                                + result.applied().size()
                                + " finding(s) in "
                                + c.script.getName()
                                + ":");
        for (FixApplier.Applied a : result.applied())
            env.out()
                    .println(
                            "  "
                                    + a.rule().toUpperCase(Locale.ROOT)
                                    + " line "
                                    + a.line()
                                    + ": "
                                    + a.label());
        env.out()
                .println(
                        "Reviewed again below. Every fix was checked: the script loads and no new high finding appeared.");
        return null;
    }
}
