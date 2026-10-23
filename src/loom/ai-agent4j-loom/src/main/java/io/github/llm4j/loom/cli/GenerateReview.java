package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.generate.ReviewGate;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.fix.FixApplier;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.FixSafety;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine;

/**
 * The review of {@code weave review --no-env} behind the generator's G2: SAFE fixes applied by the
 * tool, then what is left.
 */
final class GenerateReview {

    private GenerateReview() {}

    /**
     * A review that changes nothing: the open findings and what each workflow can cost, for
     * comparing two versions.
     */
    static io.github.llm4j.loom.generate.DeltaGate.Reviewer snapshotter(WeaveEnv env) {
        return script -> {
            ReviewCommand c = new ReviewCommand();
            new CommandLine(c).parseArgs(script.toString(), "--format", "json", "--no-env");
            ReviewSession session;
            try {
                session = ReviewCommand.open(c, env).session();
            } catch (ReviewCommand.SetupError e) {
                throw new IllegalStateException(e.getMessage());
            }
            ReviewReport report = session.review(script);
            List<io.github.llm4j.loom.generate.DeltaGate.Found> open = new ArrayList<>();
            for (Finding f : report.findings())
                open.add(
                        new io.github.llm4j.loom.generate.DeltaGate.Found(
                                f.fingerprint(),
                                f.rule(),
                                f.severity(),
                                f.where().line(),
                                f.title()));
            java.util.Map<String, io.github.llm4j.loom.generate.DeltaGate.WorkflowTokens> cost =
                    new java.util.LinkedHashMap<>();
            if (report.cost() != null)
                for (var w : report.cost().workflows())
                    cost.put(
                            w.name(),
                            new io.github.llm4j.loom.generate.DeltaGate.WorkflowTokens(
                                    w.typical().tokens(), w.worst().tokens(), w.unbounded()));
            return new io.github.llm4j.loom.generate.DeltaGate.Snapshot(open, cost);
        };
    }

    static ReviewGate.Reviewer reviewer(WeaveEnv env) {
        return script -> {
            ReviewCommand c = new ReviewCommand();
            new CommandLine(c).parseArgs(script.toString(), "--format", "json", "--no-env");
            ReviewSession session;
            try {
                session = ReviewCommand.open(c, env).session();
            } catch (ReviewCommand.SetupError e) {
                throw new IllegalStateException(e.getMessage());
            }
            String text = Files.readString(script, StandardCharsets.UTF_8);
            List<String> fixed = new ArrayList<>();
            FixApplier.Result applied =
                    FixApplier.run(
                            text,
                            f -> f.fix().isPresent() && f.fix().get().safety() == FixSafety.SAFE,
                            session::reviewText);
            if (applied.changed(text)) {
                Files.writeString(script, applied.text(), StandardCharsets.UTF_8);
                applied.applied()
                        .forEach(
                                a ->
                                        fixed.add(
                                                a.rule()
                                                        + " at line "
                                                        + a.line()
                                                        + ": "
                                                        + a.label()));
            }
            ReviewReport report = session.reviewText(applied.text());
            List<ReviewGate.Item> open = new ArrayList<>();
            for (Finding f : report.findings())
                open.add(
                        new ReviewGate.Item(
                                f.rule(),
                                f.lens(),
                                f.severity(),
                                f.where().line(),
                                f.title(),
                                f.why(),
                                f.fixHint()));
            return new ReviewGate.Reviewed(open, fixed);
        };
    }
}
