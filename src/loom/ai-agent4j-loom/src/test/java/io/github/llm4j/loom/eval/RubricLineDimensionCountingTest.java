package io.github.llm4j.loom.eval;

import static io.github.llm4j.loom.eval.EvalTestSupport.executor;
import static io.github.llm4j.loom.eval.EvalTestSupport.finalAnswer;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.dataset.EvalScenarios;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.judge.JudgeVerdict;
import io.github.llm4j.loom.eval.EvalTestSupport.Models;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * F22: a rubric line could not belong to one dimension, so each verdict was counted under every
 * dimension of its scenario. A line with its own dimension counts there only; a plain line keeps
 * counting under all of them.
 */
class RubricLineDimensionCountingTest {
    private static final String SCRIPT = "agent Helper { model: \"m\" system: \"You help.\" }\n"
            + "workflow Main(t) { delegate \"{t}\" to Helper -> r\n return r }\n";

    private static EvalScenario scenario() {
        return EvalScenarios.fromYaml(
                        new ByteArrayInputStream(
                                ("- id: a-1\n  input: q\n  dimensions: [grounding, tone]\n  rubric:\n"
                                                + "    - Cites its sources\n"
                                                + "    - { text: Uses plain English, dimension: tone }\n")
                                        .getBytes(StandardCharsets.UTF_8)))
                .get(0);
    }

    @Test
    void theRunnerKeepsEachLinesDimensionOnItsCheck() {
        Models models = new Models((system, n) -> finalAnswer("an answer"));
        EvalRunner runner =
                new EvalRunner(
                        before -> executor(SCRIPT, models, new ToolRegistry(), before),
                        model -> (criteria, subject, s) -> new JudgeVerdict(0.9, "fine"),
                        name -> "m");

        ScenarioResult r = runner.agent("f", "Helper", scenario());

        assertThat(r.checks()).extracting(ScenarioResult.Check::text)
                .containsExactly("Cites its sources", "Uses plain English");
        assertThat(r.checks().get(0).dimension()).isNull();
        assertThat(r.checks().get(1).dimension()).isEqualTo("tone");
    }

    private static EvalReport.Run run(ScenarioResult... results) {
        return new EvalReport.Run("main.loom", false, List.of(results), 0, null, Map.of());
    }

    private static ScenarioResult result(ScenarioResult.Check... checks) {
        return new ScenarioResult("Helper", "agent", "f", "a-1", "a-1", Status.PASS, List.of(checks),
                BigDecimal.ZERO, null, "q", "o", List.of(), List.of("grounding", "tone"), List.of());
    }

    @Test
    void aLineWithItsOwnDimensionCountsThereOnlyAndAPlainLineCountsUnderEvery() {
        String text =
                EvalSections.terminal(
                        run(
                                result(
                                        new ScenarioResult.Check("rubric", "plain", Status.PASS, "ok"),
                                        new ScenarioResult.Check("rubric", "tone line", Status.FAIL, "no", "tone"))));

        String dims = text.substring(text.indexOf("== By dimension =="), text.indexOf("== By slice =="));
        assertThat(row(dims, "grounding")).contains("1 passed, 0 failed");
        assertThat(row(dims, "tone")).contains("1 passed, 1 failed");
    }

    @Test
    void aCautionNamesTheLinesStillCountedUnderEveryDimension() {
        String text =
                EvalSections.terminal(
                        run(result(new ScenarioResult.Check("rubric", "plain", Status.PASS, "ok"))));

        assertThat(text).contains("counted under every dimension").contains("dimension:");
    }

    @Test
    void noCautionWhenEveryLineHasItsOwnDimension() {
        String text =
                EvalSections.terminal(
                        run(
                                result(
                                        new ScenarioResult.Check("rubric", "a", Status.PASS, "ok", "tone"),
                                        new ScenarioResult.Check("rubric", "b", Status.PASS, "ok", "grounding"))));

        assertThat(text).doesNotContain("counted under every dimension");
    }

    private static String row(String section, String dimension) {
        return section.lines().filter(l -> l.stripLeading().startsWith(dimension + " ")).findFirst().orElse("");
    }
}
