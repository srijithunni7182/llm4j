package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.handle.AcceptWriter;
import io.github.llm4j.loom.review.handle.Baseline;
import io.github.llm4j.loom.review.handle.NoiseReport;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What {@code weave review} does with findings besides listing them: accept them (with a reason, a
 * scope and optionally an end date), write or prune the baseline, and report which rules are noisy.
 * Kept apart from the command so the command stays a thin reader of options.
 */
final class ReviewHandling {

    private ReviewHandling() {}

    /** True when an option asks for one of these actions instead of the plain report. */
    static boolean requested(ReviewCommand c) {
        return c.accept != null || c.baselineWrite || c.baselinePrune || c.noiseReport;
    }

    /** Runs the requested action and returns the exit code. */
    static int apply(
            ReviewCommand c,
            ReviewReport report,
            Path dir,
            Path baselineFile,
            LocalDate today,
            WeaveEnv env) {
        if (c.baselineWrite) return writeBaseline(c, report, baselineFile, today, env);
        if (c.baselinePrune) return pruneBaseline(report, baselineFile, env);
        if (c.noiseReport) {
            env.out().print(NoiseReport.write(report, c.noiseShare));
            return 0;
        }
        return accept(c, report, dir, today, env);
    }

    private static int writeBaseline(
            ReviewCommand c, ReviewReport report, Path file, LocalDate today, WeaveEnv env) {
        try {
            Baseline.Written w =
                    Baseline.write(
                            file, report.findings(), c.baselineIncludeHigh, today, c.baselineNote);
            env.out()
                    .println(
                            "Wrote " + file + ": " + w.recorded() + " findings recorded as known.");
            if (w.leftOut() > 0)
                env.out()
                        .println(
                                w.leftOut()
                                        + " serious findings (high, or in security, durability or privacy) were left out and stay open. Add --baseline-include-high to record them as well.");
            env.out()
                    .println(
                            "Later reviews list these apart and count only new findings. Run and deploy are not affected either way.");
            return 0;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
    }

    private static int pruneBaseline(ReviewReport report, Path file, WeaveEnv env) {
        Set<String> still =
                java.util.stream.Stream.concat(
                                report.findings().stream(), report.baselined().stream())
                        .map(Finding::fingerprint)
                        .collect(Collectors.toSet());
        try {
            int removed = Baseline.prune(file, still);
            env.out()
                    .println(
                            "Removed "
                                    + removed
                                    + " entries from "
                                    + file
                                    + " whose finding is gone.");
            return 0;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
    }

    private static int accept(
            ReviewCommand c, ReviewReport report, Path dir, LocalDate today, WeaveEnv env) {
        if (c.reason == null || c.reason.isBlank()) {
            env.err().println("Error: --accept needs --reason \"why this is fine\".");
            return 2;
        }
        AcceptWriter.Scope scope;
        try {
            scope = AcceptWriter.Scope.valueOf(c.scope.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            env.err().println("Error: --scope takes finding, thing, script or project.");
            return 2;
        }
        LocalDate until = null;
        if (c.until != null) {
            try {
                until = LocalDate.parse(c.until);
            } catch (RuntimeException e) {
                env.err().println("Error: --until takes a date such as 2026-12-31.");
                return 2;
            }
        }
        List<Finding> targets = new ArrayList<>();
        for (Finding f : report.findings())
            if (f.fingerprint().equals(c.accept) || f.rule().equalsIgnoreCase(c.accept))
                targets.add(f);
        if (targets.isEmpty()) {
            env.err()
                    .println(
                            "Error: no open finding has the fingerprint or rule " + c.accept + ".");
            return 2;
        }
        if (targets.size() > 1
                && targets.stream().anyMatch(AcceptWriter::serious)
                && (c.confirm == null || c.confirm != targets.size())) {
            env.err()
                    .println(
                            "Error: this would accept "
                                    + targets.size()
                                    + " serious findings. Type the number to confirm: --confirm "
                                    + targets.size());
            return 2;
        }
        String by = c.by != null ? c.by : System.getProperty("user.name", "");
        List<String> lines = new ArrayList<>();
        try {
            for (Finding f : targets)
                lines.add(AcceptWriter.line(f, scope, thing(c, f), c.reason, until, by, today));
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        Path file =
                c.rulesFile != null ? c.rulesFile.toPath() : dir.resolve(ReviewSettings.FILE_NAME);
        try {
            int written = AcceptWriter.append(file, lines);
            env.out().println("Accepted " + written + " finding(s) in " + file + ":");
            lines.forEach(l -> env.out().println("  " + l));
            env.out()
                    .println(
                            "They stay listed under accepted in every report. Run and deploy are not affected.");
            return 0;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
    }

    /** The thing a finding is about, for the "this rule on this agent or workflow" scope. */
    private static String thing(ReviewCommand c, Finding f) {
        if (c.on != null) return c.on;
        String a = f.anchor();
        int colon = a.indexOf(':');
        if (colon < 0) return null;
        String rest = a.substring(colon + 1);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}
