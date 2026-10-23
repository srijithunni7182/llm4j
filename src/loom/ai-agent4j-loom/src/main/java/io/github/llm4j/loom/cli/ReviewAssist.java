package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.fix.FixApplier;
import io.github.llm4j.loom.review.fix.UnifiedDiff;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.FixOption;
import io.github.llm4j.loom.review.model.FixSafety;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code weave review --assist} and {@code --fix-finding FP --option ID}: the developer decides. A
 * finding that has more than one reasonable resolution offers them as options; the developer picks
 * one, answers its questions, sees the change and confirms it. Every change is checked like a fix:
 * the script must still load and no new high finding may appear. Nothing is written without the
 * developer's say: interactively by confirming, from a script or a skill by naming the finding, the
 * option and the answers (and {@code --yes} for a change that needs judgement).
 */
final class ReviewAssist {

    private ReviewAssist() {}

    /** Returns an exit code when finished, else null to carry on and report what remains. */
    static Integer run(ReviewCommand c, ReviewSession session, ReviewReport report, WeaveEnv env) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (String a : c.answers) {
            int eq = a.indexOf('=');
            if (eq < 1) {
                env.err().println("Error: --set takes name=value, not '" + a + "'.");
                return 2;
            }
            answers.put(a.substring(0, eq).strip(), a.substring(eq + 1));
        }
        if (c.option != null) {
            if (c.fixFinding == null) {
                env.err().println("Error: --option needs --fix-finding <fingerprint>.");
                return 2;
            }
            return named(c, session, answers, env);
        }
        return interactive(c, session, report, env);
    }

    // ---- --fix-finding FP --option ID [--set k=v]

    private static Integer named(
            ReviewCommand c, ReviewSession session, Map<String, String> answers, WeaveEnv env) {
        Path file = c.script.toPath();
        String original = read(file, env);
        if (original == null) return 2;
        FixApplier.Chosen chosen =
                FixApplier.applyOption(
                        original, c.fixFinding, c.option, answers, session::reviewText);
        if (!chosen.ok()) {
            env.err().println("Error: " + chosen.error() + ".");
            return 2;
        }
        FixSafety safety = safety(session, original, c.fixFinding, c.option);
        if (c.dryRun) {
            String diff = UnifiedDiff.of(c.script.getName(), original, chosen.text());
            env.out().print(diff.isEmpty() ? "No change.\n" : diff);
            return diff.isEmpty() ? 0 : 1;
        }
        if (safety == FixSafety.REVIEW && !c.yes) {
            env.err()
                    .println(
                            "Error: this option needs your judgement. Look at it with --dry-run, then repeat with --yes to apply it.");
            return 2;
        }
        if (!write(file, c.script.getName(), chosen.text(), env)) return 2;
        env.out()
                .println(
                        "Applied option '"
                                + c.option
                                + "' to finding "
                                + c.fixFinding
                                + " in "
                                + c.script.getName()
                                + ". Checked: the script loads and no new high finding appeared.");
        return null;
    }

    private static FixSafety safety(
            ReviewSession session, String text, String fingerprint, String optionId) {
        for (Finding f : session.reviewText(text).findings())
            if (f.fingerprint().equals(fingerprint))
                for (FixOption o : f.options()) if (o.id().equals(optionId)) return o.safety();
        return FixSafety.REVIEW;
    }

    // ---- --assist

    private static Integer interactive(
            ReviewCommand c, ReviewSession session, ReviewReport first, WeaveEnv env) {
        Path file = c.script.toPath();
        Set<String> done = new HashSet<>();
        int applied = 0;
        while (true) {
            String text = read(file, env);
            if (text == null) return 2;
            Finding next = null;
            for (Finding f : session.reviewText(text).findings())
                if (!f.options().isEmpty()
                        && !done.contains(f.fingerprint())
                        && (c.fixRule == null || f.rule().equalsIgnoreCase(c.fixRule))
                        && (c.fixFinding == null || f.fingerprint().equals(c.fixFinding))) {
                    next = f;
                    break;
                }
            if (next == null) break;
            done.add(next.fingerprint());
            if (decide(c, session, text, next, env)) applied++;
        }
        env.out()
                .println(
                        applied == 0
                                ? "Nothing changed."
                                : applied + " change(s) made to " + c.script.getName() + ".");
        return null;
    }

    private static boolean decide(
            ReviewCommand c, ReviewSession session, String text, Finding f, WeaveEnv env) {
        var out = env.out();
        out.println();
        out.println(
                f.severity().word().toUpperCase(Locale.ROOT)
                        + " "
                        + f.rule()
                        + " line "
                        + f.where().line()
                        + ": "
                        + f.title());
        if (!f.why().isBlank()) out.println("  why: " + f.why());
        out.println("  What would you like to do?");
        int n = 1;
        for (FixOption o : f.options()) {
            out.println(
                    "  "
                            + n++
                            + ") "
                            + o.label()
                            + (o.safety() == FixSafety.REVIEW ? "  (think first)" : ""));
            if (!o.explain().isBlank()) out.println("       " + o.explain());
        }
        out.println("  s) skip this one   a) accept it (record why)");
        String pick = env.human().promptHuman("Choose 1-" + (n - 1) + ", s or a: ").strip();
        if (pick.equalsIgnoreCase("a")) {
            out.println(
                    "  To accept it, run: weave review --accept "
                            + f.fingerprint()
                            + " (see --help).");
            return false;
        }
        FixOption chosen = null;
        try {
            int i = Integer.parseInt(pick);
            if (i >= 1 && i < n) chosen = f.options().get(i - 1);
        } catch (NumberFormatException ignored) {
            // anything else is a skip
        }
        if (chosen == null) {
            out.println("  Skipped.");
            return false;
        }
        Map<String, String> answers = new LinkedHashMap<>();
        for (FixOption.Param p : chosen.params()) {
            String suffix = p.defaultValue() == null ? "" : " [" + p.defaultValue() + "]";
            String a = env.human().promptHuman(p.prompt() + suffix + ": ").strip();
            if (!a.isEmpty()) answers.put(p.name(), a);
            else if (p.defaultValue() == null) {
                out.println("  No answer, so nothing was changed.");
                return false;
            }
        }
        FixApplier.Chosen result =
                FixApplier.applyOption(
                        text, f.fingerprint(), chosen.id(), answers, session::reviewText);
        if (!result.ok()) {
            out.println("  Not applied: " + result.error() + ".");
            return false;
        }
        out.print(UnifiedDiff.of(c.script.getName(), text, result.text()));
        String ok = env.human().promptHuman("Apply this change? [y/N]: ").strip();
        if (!ok.equalsIgnoreCase("y") && !ok.equalsIgnoreCase("yes")) {
            out.println("  Not applied.");
            return false;
        }
        return write(c.script.toPath(), c.script.getName(), result.text(), env);
    }

    // ---- files

    private static String read(Path file, WeaveEnv env) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            env.err().println("Error: " + file + " could not be read: " + e.getMessage());
            return null;
        }
    }

    private static boolean write(Path file, String name, String text, WeaveEnv env) {
        try {
            Path tmp = file.resolveSibling("." + name + ".fix-" + System.nanoTime());
            Files.writeString(tmp, text);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            env.err().println("Error: " + file + " could not be written: " + e.getMessage());
            return false;
        }
    }
}
