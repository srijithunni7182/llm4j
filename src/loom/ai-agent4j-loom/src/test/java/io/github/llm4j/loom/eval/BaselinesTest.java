package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F62: a baseline that anyone can overwrite, compared across datasets that changed, hides decline.
 * A dataset has an identity (its declared version and a hash of its cases); a baseline is saved with
 * an approver and a reason and never replaced; a comparison shows the current and the previous
 * baseline, so a slow decline across releases is visible.
 */
class BaselinesTest {
    @TempDir Path dir;

    private Path dataset() throws Exception {
        Path golden = Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(golden.resolve("helper.yaml"), "- {id: a, input: q}\n- {id: b, input: q}\n");
        Files.writeString(golden.resolve("dataset.yaml"), "version: 3\ndimensions: [accuracy]\n");
        return golden;
    }

    private static EvalReport.Run run(Status... statuses) {
        List<ScenarioResult> results = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++)
            results.add(new ScenarioResult("Helper", "agent", "f", "c" + i, "c" + i, statuses[i],
                    List.of(new ScenarioResult.Check("rubric", "r", statuses[i], "d")), BigDecimal.ZERO, null));
        return new EvalReport.Run("main.loom", false, results, 0, null, Map.of());
    }

    @Test
    void theIdentityIsTheDeclaredVersionAndAHashOfEveryCase() throws Exception {
        Path golden = dataset();

        DatasetIdentity id = DatasetIdentity.of(golden);

        assertThat(id.declared()).isEqualTo("3");
        assertThat(id.hash()).hasSize(12).matches("[0-9a-f]+");
        assertThat(id.describe()).contains("version 3").contains(id.hash());
    }

    @Test
    void changingACaseChangesTheHashAndLeavingItDoesNot() throws Exception {
        Path golden = dataset();
        DatasetIdentity before = DatasetIdentity.of(golden);
        assertThat(DatasetIdentity.of(golden)).isEqualTo(before);

        Files.writeString(golden.resolve("helper.yaml"), "- {id: a, input: q}\n- {id: b, input: a different question}\n");

        assertThat(DatasetIdentity.of(golden).hash()).isNotEqualTo(before.hash());
    }

    @Test
    void aDatasetWithNoDeclaredVersionSaysSo() throws Exception {
        Path golden = Files.createDirectories(dir.resolve("g"));
        Files.writeString(golden.resolve("helper.yaml"), "- {id: a, input: q}\n");

        assertThat(DatasetIdentity.of(golden).describe()).contains("no version declared");
    }

    @Test
    void aBaselineNeedsAnApproverAndAReason() throws Exception {
        Path golden = dataset();

        assertThatThrownBy(() -> Baselines.save(dir, DatasetIdentity.of(golden), run(Status.PASS), " ", "why", Instant.now()))
                .hasMessageContaining("approver");
        assertThatThrownBy(() -> Baselines.save(dir, DatasetIdentity.of(golden), run(Status.PASS), "ana", "", Instant.now()))
                .hasMessageContaining("reason");
    }

    @Test
    void aMockRunCannotBeABaseline() throws Exception {
        Path golden = dataset();
        EvalReport.Run mock = new EvalReport.Run("main.loom", true, List.of(), 0, null, Map.of());

        assertThatThrownBy(() -> Baselines.save(dir, DatasetIdentity.of(golden), mock, "ana", "why", Instant.now()))
                .hasMessageContaining("mock");
    }

    @Test
    void baselinesAreKeptInOrderAndNeverReplaced() throws Exception {
        Path golden = dataset();
        DatasetIdentity id = DatasetIdentity.of(golden);
        Instant t0 = Instant.parse("2026-10-01T10:00:00Z");

        Baselines.save(dir, id, run(Status.PASS, Status.FAIL), "ana", "first", t0);
        Baselines.save(dir, id, run(Status.PASS, Status.PASS), "ben", "second", t0.plusSeconds(86400));

        List<Baselines.Baseline> all = Baselines.load(dir);
        assertThat(all).extracting(Baselines.Baseline::approver).containsExactly("ana", "ben");
        assertThat(all.get(0).why()).isEqualTo("first");
        assertThat(Baselines.save(dir, id, run(Status.PASS), "cy", "third", t0.plusSeconds(86400)))
                .as("a second save in the same second gets its own file")
                .isNotEqualTo(Baselines.save(dir, id, run(Status.PASS), "cy", "fourth", t0.plusSeconds(86400)));
    }

    @Test
    void theComparisonShowsTheCurrentAndThePreviousBaselineAndWhatRegressed() throws Exception {
        Path golden = dataset();
        DatasetIdentity id = DatasetIdentity.of(golden);
        Instant t0 = Instant.parse("2026-10-01T10:00:00Z");
        Baselines.save(dir, id, run(Status.PASS, Status.PASS, Status.PASS, Status.PASS), "ana", "release 1", t0);
        Baselines.save(dir, id, run(Status.PASS, Status.PASS, Status.PASS, Status.FAIL), "ben", "release 2", t0.plusSeconds(86400));

        List<String> lines = Baselines.compare(Baselines.load(dir), run(Status.PASS, Status.PASS, Status.FAIL, Status.FAIL), id);

        String text = String.join("\n", lines);
        assertThat(text).contains("release 2").contains("ben").contains("release 1").contains("ana");
        assertThat(text).contains("100%").contains("75%").contains("50%");
        assertThat(text).contains("regressed").contains("c2");
        assertThat(text).doesNotContain("c3 ");
    }

    @Test
    void aDifferentDatasetIsRefusedUnlessTheReasonIsGiven() throws Exception {
        Path golden = dataset();
        Baselines.save(dir, DatasetIdentity.of(golden), run(Status.PASS), "ana", "why", Instant.now());
        Files.writeString(golden.resolve("helper.yaml"), "- {id: a, input: changed}\n");
        DatasetIdentity changed = DatasetIdentity.of(golden);

        assertThat(Baselines.mismatch(Baselines.load(dir), changed)).isPresent();
        assertThat(Baselines.mismatch(Baselines.load(dir), changed).get()).contains("different dataset").contains("--allow-version-change");
    }

    @Test
    void noBaselineYetSaysSoAndIsNotAnError() throws Exception {
        assertThat(Baselines.compare(List.of(), run(Status.PASS), DatasetIdentity.of(dataset())))
                .singleElement()
                .asString()
                .contains("no baseline");
    }
}
