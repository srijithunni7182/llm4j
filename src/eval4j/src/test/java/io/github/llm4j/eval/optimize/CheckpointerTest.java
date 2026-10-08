package io.github.llm4j.eval.optimize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.eval.criteria.CriterionOutcome;
import io.github.llm4j.eval.criteria.CriterionResult;
import io.github.llm4j.eval.criteria.Scorecard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckpointerTest {

    private static CheckpointState state(String fingerprint) {
        Candidate seed = Candidate.of("p", "seed \"quoted\"\nnewline");
        Scorecard card =
                new Scorecard(
                        "s1",
                        0.5,
                        false,
                        List.of(
                                new CriterionOutcome(
                                        "c", new CriterionResult(0.5, false, "why"), false, false)),
                        "out",
                        "feedback",
                        false);
        return new CheckpointState(
                fingerprint,
                3,
                1,
                0,
                4,
                120,
                6,
                4500,
                List.of(card),
                List.of(
                        new CheckpointState.EntryState(
                                CandidateSnapshot.of(seed), new double[] {0.5, 0.25}, true)),
                List.of(Map.of("p", "seed")),
                List.of(
                        new Round(
                                1,
                                "c0",
                                "p",
                                RoundAction.ACCEPTED,
                                "c1",
                                List.of("a", "b"),
                                0.2,
                                0.4,
                                0.5,
                                true,
                                2,
                                1,
                                "")));
    }

    @Test
    void roundTripsEveryField(@TempDir Path dir) {
        Checkpointer checkpointer = new Checkpointer(dir);
        checkpointer.save(state("fp"));

        CheckpointState loaded = checkpointer.load().orElseThrow();

        assertThat(loaded.fingerprint()).isEqualTo("fp");
        assertThat(loaded.completedRounds()).isEqualTo(3);
        assertThat(loaded.rollouts()).isEqualTo(120);
        assertThat(loaded.seedValidation()).isEqualTo(state("fp").seedValidation());
        assertThat(loaded.entries().get(0).validationScores()).containsExactly(0.5, 0.25);
        assertThat(loaded.entries().get(0).candidate().toCandidate().get("p"))
                .isEqualTo("seed \"quoted\"\nnewline");
        assertThat(loaded.entries().get(0).guardrailViolation()).isTrue();
        assertThat(loaded.trace()).isEqualTo(state("fp").trace());
        assertThat(loaded.proposed()).containsExactly(Map.of("p", "seed"));
    }

    @Test
    void noFileMeansNoCheckpoint(@TempDir Path dir) {
        assertThat(new Checkpointer(dir).load()).isEmpty();
    }

    @Test
    void aCorruptFileIsReportedNotSilentlyIgnored(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(Checkpointer.FILE_NAME), "{ this is not json");
        assertThatThrownBy(() -> new Checkpointer(dir).load())
                .isInstanceOf(OptimizerConfigurationException.class)
                .hasMessageContaining("could not be read")
                .hasMessageContaining("Delete it to start a fresh run");
    }

    @Test
    void savingLeavesNoTempFilesAndCreatesTheDirectory(@TempDir Path dir) throws Exception {
        Path nested = dir.resolve("a/b");
        new Checkpointer(nested).save(state("fp"));
        try (var files = Files.list(nested)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .containsExactly(Checkpointer.FILE_NAME);
        }
    }

    @Test
    void aFailedWriteLeavesThePreviousCheckpointIntact(@TempDir Path dir) throws Exception {
        Checkpointer checkpointer = new Checkpointer(dir);
        checkpointer.save(state("first"));
        // make the target a non-empty directory so the atomic move must fail
        Path file = dir.resolve(Checkpointer.FILE_NAME);
        byte[] original = Files.readAllBytes(file);
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("blocker"), "x");

        assertThatThrownBy(() -> checkpointer.save(state("second")))
                .isInstanceOf(IllegalStateException.class);

        Files.delete(file.resolve("blocker"));
        Files.delete(file);
        Files.write(file, original);
        assertThat(checkpointer.load().orElseThrow().fingerprint()).isEqualTo("first");
    }
}
