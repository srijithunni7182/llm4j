package io.github.llm4j.provider.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every fixture says where it came from; a recorded one says when and with which model, and none
 * holds a key or an account id.
 */
class FixtureProvenanceTest {

    private static final Set<String> SOURCES =
            Set.of("recorded", "recorded-through-perplexity", "documentation-derived");

    private static List<Path> fixtures() throws IOException, URISyntaxException {
        Path root = Paths.get(FixtureProvenanceTest.class.getResource("/providers").toURI());
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".fixture")).sorted().toList();
        }
    }

    @Test
    void everyFixtureStatesItsSource() throws Exception {
        assertThat(fixtures()).isNotEmpty();
        for (Path p : fixtures()) {
            String relative =
                    Paths.get(FixtureProvenanceTest.class.getResource("/providers").toURI())
                            .relativize(p)
                            .toString();
            Fixtures f = Fixtures.load(relative);
            assertThat(f.source()).as(relative).isIn(SOURCES);
            if (f.recorded()) {
                assertThat(f.header()).as(relative).containsKeys("date", "model");
            } else {
                assertThat(f.header().get("note"))
                        .as(relative)
                        .contains("not recorded from the real API");
            }
        }
    }

    @Test
    void noFixtureHoldsAKeyOrAnAccountId() throws Exception {
        for (Path p : fixtures()) {
            String text = Files.readString(p);
            assertThat(text)
                    .as(p.toString())
                    .doesNotContainPattern("sk-[A-Za-z0-9_-]{16,}")
                    .doesNotContainPattern("xai-[A-Za-z0-9]{20,}")
                    .doesNotContainPattern("pplx-[A-Za-z0-9]{20,}")
                    .doesNotContainPattern("org-[A-Za-z0-9]{10,}")
                    .doesNotContainPattern("(?i)bearer [A-Za-z0-9_-]{16,}");
        }
    }
}
