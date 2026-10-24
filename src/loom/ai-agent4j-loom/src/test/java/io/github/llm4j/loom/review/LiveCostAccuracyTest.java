package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.review.cost.CostEstimate;
import io.github.llm4j.loom.review.cost.CostEstimator;
import io.github.llm4j.loom.review.cost.WorkflowCost;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The live check of A4 (VERIFICATION.md, manual check 3): the same ten cases as {@link
 * CorpusCostAccuracyTest}, run against a real provider. It is off unless {@code LLM4J_LIVE=1} and
 * {@code OPENAI_API_KEY} are set, because it spends money: a few tens of thousands of tokens on the
 * cheapest model, with a hard cap per run and a cap on the total.
 */
@EnabledIfEnvironmentVariable(named = "LLM4J_LIVE", matches = "1")
class LiveCostAccuracyTest {

    static final String MODEL = System.getenv().getOrDefault("LLM4J_LIVE_MODEL", "gpt-4.1-nano");
    static final long TOTAL_CAP = 150_000;

    @TempDir Path dir;

    @Test
    void theEstimateAgainstARealProvider() throws Exception {
        long spent = 0;
        StringBuilder table =
                new StringBuilder(
                        "Provider model: "
                                + MODEL
                                + "\n\n| Case | Estimated typical | Actual | Ratio | Estimated worst | Worst >= actual |\n|---|---|---|---|---|---|\n");
        List<String> worstBelow = new ArrayList<>();
        int within = 0;
        List<CorpusCostAccuracyTest.Case> cases = CorpusCostAccuracyTest.cases();
        for (CorpusCostAccuracyTest.Case mock : cases) {
            if (spent > TOTAL_CAP) break;
            String script =
                    mock.script()
                            .replace("budget { tokens: 100000000 }", "budget { tokens: 40000 }")
                            .replace("model: \"m\"", "model: \"" + MODEL + "\"")
                            .replace("model: \"n\"", "model: \"" + MODEL + "\"");
            CorpusCostAccuracyTest.Case live =
                    new CorpusCostAccuracyTest.Case(
                            mock.name(), script, mock.args(), mock.answer());
            Path run = Files.createDirectories(dir.resolve(live.name()));
            long actual;
            try {
                actual = CorpusCostAccuracyTest.record(live, run, new DefaultLLMClientFactory());
            } catch (RuntimeException e) {
                table.append("| ")
                        .append(live.name())
                        .append(" | failed: ")
                        .append(e.getMessage())
                        .append(" | | | | |\n");
                continue;
            }
            spent += actual;
            CostEstimate e =
                    new CostEstimator()
                            .estimate(ReviewFixtures.input(run, script), ReviewSettings.defaults());
            WorkflowCost main =
                    e.workflows().stream()
                            .filter(w -> w.name().equals("Main"))
                            .findFirst()
                            .orElseThrow();
            long typical = main.typical().tokens();
            double ratio = actual == 0 ? 0 : (double) typical / actual;
            if (ratio >= 0.5 && ratio <= 2.0) within++;
            boolean worstOk = main.unbounded() || main.worst().tokens() >= actual;
            if (!worstOk) worstBelow.add(live.name());
            table.append(
                    String.format(
                            "| %s | %d | %d | %.2f | %s | %s |%n",
                            live.name(),
                            typical,
                            actual,
                            ratio,
                            main.unbounded() ? "unbounded" : String.valueOf(main.worst().tokens()),
                            worstOk));
        }
        table.append(
                String.format(
                        "%nWithin 2x: %d of %d. Tokens spent: %d.%n", within, cases.size(), spent));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/review-cost-accuracy-live.md"), table.toString());
        System.out.println(table);
        assertThat(spent).isGreaterThan(0);
    }
}
