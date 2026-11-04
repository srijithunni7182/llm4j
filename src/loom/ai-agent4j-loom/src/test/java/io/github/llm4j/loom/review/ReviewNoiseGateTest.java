package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The noise gate of the engineering discipline program (D35 E, program page section 4.6): a rule the
 * program added must not fire on most of the reference corpus (the starters), or people stop
 * reading review. A rule over the bar is retuned or demoted before it ships. Each phase adds its
 * rule ids to {@code NEW_RULES}.
 */
class ReviewNoiseGateTest {

    /** The rules the program has added so far. */
    private static final Set<String> NEW_RULES = Set.of("LP08", "LP09", "LP10", "LA21", "LT01", "LT06", "LR12", "LR14");

    /** The most scripts of the corpus a new rule may fire on, as a share. */
    private static final double MOST_SCRIPTS_FIRING = 0.5;

    private static final Path STARTERS = Path.of("../starters").toAbsolutePath().normalize();

    private static Set<String> testabilityRules(Path script) throws Exception {
        io.github.llm4j.loom.ast.LoomScript loaded =
                new io.github.llm4j.loom.execution.LoomLoader().load(script.toString());
        var input =
                new io.github.llm4j.loom.review.engine.ReviewInput(
                        script.getFileName().toString(),
                        script.toAbsolutePath().getParent(),
                        loaded,
                        Map.of(),
                        java.util.List.of(),
                        new io.github.llm4j.loom.graph.GraphService().graph(script));
        Set<String> found = new HashSet<>();
        new io.github.llm4j.loom.review.testing.TestabilityRules()
                .collect(
                        input,
                        ReviewSettings.defaults(),
                        new io.github.llm4j.loom.review.engine.FindingSource.Sink() {
                            @Override
                            public void finding(io.github.llm4j.loom.review.model.Finding f) {
                                found.add(f.rule());
                            }

                            @Override
                            public void note(io.github.llm4j.loom.review.model.Note n) {}
                        });
        return found;
    }

    @Test
    void noNewRuleFiresOnMoreThanHalfOfTheStarters() throws Exception {
        Map<String, Integer> firing = new TreeMap<>();
        int scripts = 0;
        try (Stream<Path> all = Files.list(STARTERS)) {
            for (Path dir : all.filter(Files::isDirectory).sorted().toList()) {
                Path script = dir.resolve("main.loom");
                if (!Files.exists(script)) continue;
                scripts++;
                Set<String> rules = new HashSet<>();
                ReviewCorpusTest.review(script, ReviewSettings.defaults())
                        .forEach(m -> rules.add(m.rule()));
                // the Java rules (LT01, LT06) are not in the YAML pack: run them as well
                rules.addAll(testabilityRules(script));
                for (String rule : rules)
                    if (NEW_RULES.contains(rule)) firing.merge(rule, 1, Integer::sum);
            }
        }

        final int total = scripts;
        assertThat(total).isGreaterThanOrEqualTo(15);
        firing.forEach(
                (rule, count) ->
                        assertThat((double) count / total)
                                .as(rule + " fires on " + count + " of " + total + " starters")
                                .isLessThanOrEqualTo(MOST_SCRIPTS_FIRING));
    }
}
