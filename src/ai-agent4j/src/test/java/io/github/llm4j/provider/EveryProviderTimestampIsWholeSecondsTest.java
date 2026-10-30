package io.github.llm4j.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Providers refuse sub-second times, so the one adapter that sends a time ({@code GoogleGrounding})
 * truncates it. This guards the audit: a second adapter that reads the clock must come with its own
 * whole-second test and be added to the list here.
 */
class EveryProviderTimestampIsWholeSecondsTest {
    private static final List<String> ADAPTERS_THAT_SEND_A_TIME =
            List.of("GoogleGrounding.java", "GoogleProvider.java");

    @Test
    void onlyTheAdaptersWithAWholeSecondTestReadTheClock() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/io/github/llm4j/provider"))) {
            List<String> readers =
                    files.filter(f -> f.toString().endsWith(".java"))
                            .filter(f -> contains(f, "Instant.now(") || contains(f, "LocalDate"))
                            .map(f -> f.getFileName().toString())
                            .toList();

            assertThat(readers).isSubsetOf(ADAPTERS_THAT_SEND_A_TIME);
        }
    }

    private static boolean contains(Path file, String text) {
        try {
            return Files.readString(file).contains(text);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("cannot read " + file, e);
        }
    }
}
