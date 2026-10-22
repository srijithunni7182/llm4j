package io.github.llm4j.loom.review.pack;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.GraphService;
import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.rule.Example;
import io.github.llm4j.loom.review.rule.RuleDoc;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs the examples every rule carries: each {@code fires} script must produce at least one finding
 * of that rule (or exactly the number it expects), and each {@code quiet} script none. A rule is
 * therefore tested by the pack that holds it, and a pack whose examples fail is not trustworthy.
 * Nothing is run besides parsing the example and evaluating the rule.
 */
public final class PackTester {

    private PackTester() {}

    /** How one example came out. */
    public record Result(
            String rule, String kind, String example, boolean passed, String message) {}

    public static List<Result> run(Pack pack) {
        List<Result> results = new ArrayList<>();
        for (RuleDoc rule : pack.rules()) {
            for (Example e : rule.fires()) results.add(check(pack, rule, e, true));
            for (Example e : rule.quiet()) results.add(check(pack, rule, e, false));
        }
        return results;
    }

    private static Result check(Pack pack, RuleDoc rule, Example e, boolean fires) {
        String kind = fires ? "fires" : "quiet";
        String name = e.file() != null ? e.file() : "(inline)";
        String source = e.inline() != null ? e.inline() : pack.files().read(e.file()).orElse(null);
        if (source == null)
            return new Result(
                    rule.id(),
                    kind,
                    name,
                    false,
                    "the example file '" + e.file() + "' is missing from the pack");
        int found;
        try {
            found = count(pack, rule, source);
        } catch (RuntimeException ex) {
            return new Result(
                    rule.id(),
                    kind,
                    name,
                    false,
                    "the example could not be reviewed: " + ex.getMessage());
        }
        boolean ok;
        String message;
        if (fires) {
            int want = e.expect() == null ? -1 : e.expect();
            ok = want < 0 ? found >= 1 : found == want;
            message =
                    ok
                            ? found + " finding(s)"
                            : "expected "
                                    + (want < 0 ? "at least one finding" : want + " finding(s)")
                                    + " but the rule reported "
                                    + found;
        } else {
            ok = found == 0;
            message = ok ? "no findings" : "expected no findings but the rule reported " + found;
        }
        return new Result(rule.id(), kind, name, ok, message);
    }

    /**
     * Reviews the example with only this rule, every lens and heuristics on, and counts the
     * findings.
     */
    private static int count(Pack pack, RuleDoc rule, String source) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("review-example");
            Path file = dir.resolve("main.loom");
            Files.writeString(file, source);
            LoomScript script = new LoomLoader().load(file.toString());
            GraphResult graph = new GraphService().graph(file);
            ReviewInput input =
                    new ReviewInput("main.loom", dir, script, Map.of(), List.of(), graph);
            ReviewSettings settings =
                    ReviewSettings.defaults().withLenses("all").withHeuristics(true);
            Pack one = new Pack(pack.manifest(), List.of(rule), pack.files());
            List<Finding> findings = new ArrayList<>();
            List<Note> notes = new ArrayList<>();
            new DataRuleSource(List.of(one))
                    .collect(
                            input,
                            settings,
                            new FindingSource.Sink() {
                                @Override
                                public void finding(Finding f) {
                                    findings.add(f);
                                }

                                @Override
                                public void note(Note n) {
                                    notes.add(n);
                                }
                            });
            if (findings.isEmpty() && !notes.isEmpty())
                throw new IllegalStateException(notes.get(0).message());
            return (int) findings.stream().filter(f -> f.rule().equals(rule.id())).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            if (e instanceof RuntimeException r) throw r;
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            if (dir != null) delete(dir);
        }
    }

    private static void delete(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // a temporary folder that cannot be removed is not worth failing a test run for
        }
    }
}
