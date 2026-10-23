package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * A10 and A11 offline: every description under {@code generate/descriptions} that has a recorded
 * transcript under {@code generate/transcripts} is generated again from the transcript, with no
 * model, and must pass every gate. The transcripts are recorded by {@code
 * scripts/bench-generate.sh} against a real model; a description without one is simply not
 * replayed.
 */
class GenerateBenchmarkTest {

    @TempDir Path dir;

    private static Path resources() {
        return Path.of("src/test/resources/generate");
    }

    @Test
    void thirtyDescriptionsAreKept() throws IOException {
        try (Stream<Path> s = Files.list(resources().resolve("descriptions"))) {
            assertThat(s.count()).isEqualTo(30);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyRecordedTranscriptReplaysToAProjectThatPassesTheGates()
            throws IOException {
        List<Path> recorded;
        try (Stream<Path> s = Files.list(resources().resolve("transcripts"))) {
            recorded = s.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList();
        }
        return recorded.stream()
                .map(
                        t -> {
                            String id = t.getFileName().toString().replace(".jsonl", "");
                            return dynamicTest(
                                    id,
                                    () -> {
                                        String description =
                                                Files.readString(
                                                                resources()
                                                                        .resolve(
                                                                                "descriptions/"
                                                                                        + id
                                                                                        + ".txt"))
                                                        .strip();
                                        Path out = dir.resolve(id);
                                        GenerateCommandTest.Out o =
                                                GenerateCommandTest.generate(
                                                        GenerateCommandTest.noModels(),
                                                        description,
                                                        "--replay",
                                                        t.toString(),
                                                        "--replay-by-order",
                                                        "--no-ask",
                                                        "--flat",
                                                        "--out",
                                                        out.toString());
                                        assertThat(o.err()).isEmpty();
                                        assertThat(o.code()).as(o.out()).isZero();
                                        assertThat(out.resolve("main.loom")).isRegularFile();
                                        assertThat(out.resolve("plan.json")).isRegularFile();
                                    });
                        });
    }
}
