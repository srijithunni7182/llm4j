package io.github.llm4j.loom.inspect.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * R24: the page stays a handful of static files (no build step, no framework), and the script and
 * page carry no outside address.
 */
class PageFilesTest {

    private static final Path UI = Path.of("src/main/resources/io/github/llm4j/loom/inspect/ui");

    @Test
    void thePageIsFourFilesTheTwoMarksAndTheFonts() throws IOException {
        try (Stream<Path> files = Files.walk(UI)) {
            List<String> names =
                    files.filter(Files::isRegularFile)
                            .map(p -> UI.relativize(p).toString().replace('\\', '/'))
                            .sorted()
                            .toList();
            assertThat(names)
                    .containsExactly(
                            "app.css",
                            "app.js",
                            "fabric-logo.svg",
                            "fonts/HankenGrotesk.woff2",
                            "fonts/JetBrainsMono.woff2",
                            "fonts/OFL-HankenGrotesk.txt",
                            "fonts/OFL-JetBrainsMono.txt",
                            "index.html",
                            "loom-logo.png");
        }
    }

    @Test
    void nothingInThePageReachesAnotherSite() throws IOException {
        for (String f : List.of("index.html", "app.js", "app.css", "fabric-logo.svg")) {
            String text = Files.readString(UI.resolve(f));
            assertThat(text.replace("http://www.w3.org/2000/svg", ""))
                    .as(f)
                    .doesNotContainPattern("https?://");
        }
    }
}
