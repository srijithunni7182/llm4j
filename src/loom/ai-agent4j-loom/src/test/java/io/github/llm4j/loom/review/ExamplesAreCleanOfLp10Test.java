package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.graph.GraphService;
import io.github.llm4j.loom.review.engine.FindingSource;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.pack.DataRuleSource;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 25, R4: the repository's own scripts are reference implementations, so none of them may
 * trip LP10 (an agent that reasons forced into a large schema). The scripts that exist to show a
 * rule firing are named here, with the reason, and nothing else is exempt.
 */
class ExamplesAreCleanOfLp10Test {

    /** Scripts that are test data for a rule, not examples to learn from. */
    private static final List<String> RULE_FIXTURES =
            List.of(
                    "review-rules/core/examples/", // fixtures that make one rule fire
                    "src/test/resources/", // test inputs
                    "vscode-loom/test/fixtures/", // editor fixtures, flawed on purpose
                    "lc01-quiet-tools.loom" // fixture for LC01, accepted with a reason (DESIGN R4)
                    );

    /**
     * Agents in the shipped examples that still answer in a large schema although they reason, each
     * with the reason. The host code of these examples reads the agent's structured answer in Java
     * (to annotate it, to format the live page, to count model calls exactly), or the agent is
     * re-delegated by name at run time, or its answer is itself a bundle of source files. Moving
     * them needs a hook for extract steps in the host API; see docs/specs/25-reason-then-extract
     * VERIFICATION section 7.
     */
    private static final Map<String, String> KEPT =
            Map.of(
                    "getviral.loom:Showrunner", "host code annotates its answer; some steps use a per-step schema",
                    "getviral.loom:ReelDirector", "re-delegated by name at run time to fix a failed gate; the host reads its answer",
                    "getviral.loom:YouTubeProducer", "the host checks its answer for originality in Java; re-delegated by name",
                    "crew.loom:Rishi", "the host formats the live page from its structured spec; model calls are counted exactly in tests",
                    "crew.loom:Vihaan", "its answer is a bundle of source files: an extractor would re-copy every file",
                    "main.loom:Rishi", "the same agent, seen through the import",
                    "main.loom:Vihaan", "the same agent, seen through the import",
                    "main.loom:Drishti", "its answer is a bundle of test files: an extractor would re-copy every file");

    @Test
    void noShippedExampleTripsLp10() throws Exception {
        Path repo = Path.of("../../..").toAbsolutePath().normalize();
        List<String> offenders = new ArrayList<>();
        java.util.Set<String> kept = new java.util.TreeSet<>();
        int reviewed = 0;
        try (Stream<Path> all = Files.walk(repo)) {
            for (Path script :
                    all.filter(p -> p.toString().endsWith(".loom") && Files.isRegularFile(p))
                            .filter(p -> !p.toString().contains("/target/"))
                            .filter(p -> !p.toString().contains("/node_modules/"))
                            .filter(p -> RULE_FIXTURES.stream().noneMatch(f -> p.toString().contains(f)))
                            .sorted()
                            .toList()) {
                List<Finding> found;
                try {
                    found = review(script);
                } catch (Exception | LinkageError notLoadableOnItsOwn) {
                    continue; // an import fragment, or a script that needs the host
                }
                reviewed++;
                for (Finding f : found)
                    if (f.rule().equals("LP10")) {
                        String agent = f.title().split(" ")[1];
                        if (KEPT.containsKey(script.getFileName() + ":" + agent)) {
                            kept.add(script.getFileName() + ":" + agent);
                            continue;
                        }
                        offenders.add(repo.relativize(script) + ":" + f.where().line() + " " + f.title());
                    }
            }
        }
        assertThat(reviewed).isGreaterThan(30);
        assertThat(offenders).as("agents that reason and answer in a large schema").isEmpty();
        // an exception that no longer applies must be removed, not left behind
        assertThat(kept).as("the named exceptions still trip LP10").containsExactlyInAnyOrderElementsOf(KEPT.keySet());
    }

    private static List<Finding> review(Path file) throws Exception {
        List<Finding> out = new ArrayList<>();
        new DataRuleSource(List.of(Reviews.corePack()))
                .collect(
                        new ReviewInput(
                                file.getFileName().toString(),
                                file.toAbsolutePath().getParent(),
                                new LoomLoader().load(file.toString()),
                                Map.of(),
                                List.of(),
                                new GraphService().graph(file)),
                        ReviewSettings.defaults(),
                        new FindingSource.Sink() {
                            @Override
                            public void finding(Finding f) {
                                out.add(f);
                            }

                            @Override
                            public void note(Note n) {}
                        });
        return out;
    }
}
