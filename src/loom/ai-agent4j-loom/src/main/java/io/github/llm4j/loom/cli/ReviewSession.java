package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.GraphService;
import io.github.llm4j.loom.review.cost.CostEstimator;
import io.github.llm4j.loom.review.engine.ReviewEngine;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.engine.ValidityProblem;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One review set-up (settings, packs, rules, estimator) that can review a script on disk or, for
 * the fix engine, a candidate text of it. Nothing is run; the only thing written is a short-lived
 * file beside the script while a candidate is checked, because loading and validating a script
 * needs a file.
 */
final class ReviewSession {

    private final ReviewCommand c;
    private final WeaveEnv env;
    private final ReviewSettings settings;
    private final ReviewEngine engine;
    private final Map<String, String> reach;
    private final CostEstimator estimator;

    ReviewSession(
            ReviewCommand c,
            WeaveEnv env,
            ReviewSettings settings,
            ReviewEngine engine,
            Map<String, String> reach,
            CostEstimator estimator) {
        this.c = c;
        this.env = env;
        this.settings = settings;
        this.engine = engine;
        this.reach = reach;
        this.estimator = estimator;
    }

    ReviewInput input(Path file, String text) throws Exception {
        LoomScript loaded = new LoomLoader().load(file.toAbsolutePath().toString());
        return new ReviewInput(
                c.script.getName(),
                c.script.getAbsoluteFile().getParentFile().toPath(),
                loaded,
                reach,
                validity(file.toFile(), loaded),
                graph(file),
                text);
    }

    ReviewReport review(Path file) throws Exception {
        return engine.review(input(file, null), settings);
    }

    ReviewReport review(ReviewInput input) {
        return engine.review(input, settings);
    }

    CostEstimator estimator() {
        return estimator;
    }

    ReviewSettings settings() {
        return settings;
    }

    /** Reviews candidate text: written beside the script under another name, reviewed, removed. */
    ReviewReport reviewText(String text) {
        Path dir = c.script.getAbsoluteFile().getParentFile().toPath();
        Path temp = dir.resolve(".review-candidate-" + System.nanoTime() + ".loom");
        try {
            Files.writeString(temp, text);
            return engine.review(input(temp, text), settings);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (java.io.IOException ignored) {
                // a leftover temporary file is harmless and named so it can be recognised
            }
        }
    }

    /** What weave check reports, as problems the review can read without the validator. */
    private List<ValidityProblem> validity(File file, LoomScript loaded) {
        WeaveCLI.Validation v =
                WeaveCLI.validate(
                        file,
                        c.lootFile,
                        new WeaveCLI.CheckSettings(false, false, c.noEnv, false),
                        env,
                        loaded);
        List<ValidityProblem> out = new ArrayList<>();
        for (String r : v.reachProblems()) out.add(new ValidityProblem(0, null, r, true));
        for (String w : v.keyWarnings()) out.add(new ValidityProblem(0, null, w, false));
        var error = ScriptValidator.Severity.ERROR;
        for (var p : v.problems())
            out.add(
                    new ValidityProblem(
                            p.line(), p.construct(), p.message(), p.severity() == error));
        return out;
    }

    private static GraphResult graph(Path file) {
        try {
            return new GraphService().graph(file);
        } catch (RuntimeException e) {
            return null; // the graph only adds node ids; the review does not depend on it
        }
    }
}
