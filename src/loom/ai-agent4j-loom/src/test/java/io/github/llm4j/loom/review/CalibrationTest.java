package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.review.cost.Calibration;
import io.github.llm4j.loom.review.cost.CostEstimate;
import io.github.llm4j.loom.review.cost.CostEstimator;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R16 of spec 09: estimated against what a journaled run spent. */
class CalibrationTest {

    @TempDir Path dir;

    static final String SCRIPT =
            "agent A { model: \"m\"  system: \"You are helpful\" }\n"
                    + "workflow Main(q) { loop until (r == \"y\") max 10 { delegate \"hello world\" to A -> r } }";

    CostEstimate estimate() {
        return new CostEstimator()
                .estimate(ReviewFixtures.input(dir, SCRIPT), ReviewSettings.defaults());
    }

    void usage(
            FileRunJournal j, String step, String model, long prompt, long completion, long calls) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent", "A");
        m.put("model", model);
        m.put("prompt", prompt);
        m.put("completion", completion);
        m.put("calls", calls);
        m.put("cost", "0.01");
        m.put("estimated", false);
        m.put("at", 0L);
        j.put(step + "#usage:A", new RunJournal.Entry("usage", m));
    }

    Path run(long... callsPerRound) throws Exception {
        Path run = Files.createDirectories(dir.resolve("run"));
        FileRunJournal j = new FileRunJournal(run.resolve("journal.json"));
        int i = 0;
        for (long calls : callsPerRound)
            usage(j, "Main/r" + (++i) + ".0", "m", 9 * calls, 600 * calls, calls);
        return run;
    }

    @Test
    void aRunThatMatchesTheEstimateHasARatioOfOne() throws Exception {
        // typical is 3 turns of 609 tokens
        Calibration.Result r = Calibration.compare(run(1, 1, 1), estimate());
        assertThat(r.workflows()).hasSize(1);
        assertThat(r.workflows().get(0).name()).isEqualTo("Main");
        assertThat(r.workflows().get(0).estimatedTokens()).isEqualTo(3 * 609);
        assertThat(r.workflows().get(0).actualTokens()).isEqualTo(3 * 609);
        assertThat(r.workflows().get(0).ratio()).isEqualTo(1.0);
        assertThat(r.notes()).noneMatch(n -> n.contains("times the typical estimate"));
        assertThat(r.models()).extracting(Calibration.Row::name).containsExactly("m");
    }

    @Test
    void aRunThatSpentMuchMoreNamesTheAssumptionsToCorrect() throws Exception {
        Calibration.Result r = Calibration.compare(run(1, 1, 1, 1, 1, 1, 1, 1), estimate());
        assertThat(r.workflows().get(0).ratio()).isGreaterThan(2);
        assertThat(r.notes())
                .anyMatch(
                        n ->
                                n.contains("Main used 2.7 times the typical estimate")
                                        && n.contains("assume.loop.iterations"));
        assertThat(Calibration.text(r))
                .contains("Main")
                .contains("2.67")
                .contains("One run is one sample");
    }

    @Test
    void aRunWithNoUsageSaysThereIsNothingToCompare() throws Exception {
        Path run = Files.createDirectories(dir.resolve("empty"));
        new FileRunJournal(run.resolve("journal.json"))
                .put("Main/0", new RunJournal.Entry("value", "x"));
        Calibration.Result r = Calibration.compare(run, estimate());
        assertThat(r.workflows()).isEmpty();
        assertThat(r.notes()).anyMatch(n -> n.contains("no model usage"));
    }

    @Test
    void aFolderWithoutAJournalIsAnError() {
        assertThatThrownBy(() -> Calibration.compare(dir, estimate()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no journal.json");
    }

    @Test
    void usageOfAWorkflowTheScriptDoesNotHaveIsReportedNotEstimated() throws Exception {
        Path run = Files.createDirectories(dir.resolve("other"));
        FileRunJournal j = new FileRunJournal(run.resolve("journal.json"));
        usage(j, "Other/0", "m", 10, 10, 1);
        Calibration.Result r = Calibration.compare(run, estimate());
        assertThat(r.notes())
                .anyMatch(n -> n.contains("Other spent tokens but the script has no workflow"));
    }
}
