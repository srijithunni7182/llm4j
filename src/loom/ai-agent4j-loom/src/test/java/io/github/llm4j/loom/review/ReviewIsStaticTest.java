package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A12, R64 of spec 09: review never runs a model, a tool or a task, never touches the network and
 * never starts a process. Checked on the source of the review packages, so a change that adds one
 * fails here.
 */
class ReviewIsStaticTest {

    static final Path ROOT = Path.of("src/main/java/io/github/llm4j/loom/review");

    static final List<String> FORBIDDEN =
            List.of(
                    "java.net.http",
                    "java.net.Socket",
                    "java.net.URL(",
                    "HttpURLConnection",
                    "ProcessBuilder",
                    "Runtime.getRuntime",
                    "System.getenv",
                    "Instant.now",
                    "System.currentTimeMillis",
                    "LLMClient",
                    "ToolFactory");

    @Test
    void noReviewSourceReachesTheNetworkAProcessTheEnvironmentTheClockOrAModel()
            throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f);
                for (String bad : FORBIDDEN)
                    if (text.contains(bad)) hits.add(ROOT.relativize(f) + " uses " + bad);
            }
        }
        assertThat(hits).isEmpty();
    }
}
