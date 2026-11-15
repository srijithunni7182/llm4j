package io.github.llm4j.tools.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.tools.ScanKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 36, D15 rules 3 and 4 and G94: a recorded fixture says where it came from, when, under what
 * licence and what was removed; every file it lists is there and no other file is; and the repository's own
 * {@code scan} tool finds no credential and no personal data in any of them.
 */
class FixtureProvenanceTest {

    static final Path FIXTURES = Path.of("src/test/resources/fixtures");
    static final List<String> REQUIRED = List.of("what", "service", "licence", "recorded", "scrubbed", "files");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<Path> fixtureFolders() throws IOException {
        try (Stream<Path> s = Files.list(FIXTURES)) {
            return s.filter(Files::isDirectory).sorted().toList();
        }
    }

    @Test
    void everyFixtureFolderCarriesCompleteMetadataThatListsExactlyItsFiles() throws Exception {
        assertThat(fixtureFolders()).as("at least the Petstore fixture").isNotEmpty();
        for (Path folder : fixtureFolders()) {
            JsonNode meta = JSON.readTree(Files.readString(folder.resolve("METADATA.json")));
            for (String key : REQUIRED) assertThat(meta.hasNonNull(key)).as(folder.getFileName() + " METADATA has " + key).isTrue();
            assertThat(meta.get("recorded").asText()).as("a date").matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(meta.get("scrubbed").asText()).as("what was removed, or that nothing was").isNotBlank();

            Set<String> listed = new HashSet<>();
            meta.get("files").fieldNames().forEachRemaining(listed::add);
            Set<String> present = new HashSet<>();
            try (Stream<Path> files = Files.list(folder)) {
                files.map(p -> p.getFileName().toString()).filter(n -> !n.equals("METADATA.json")).forEach(present::add);
            }
            assertThat(present).as(folder.getFileName() + " files").isEqualTo(listed);
        }
    }

    @Test
    void theScanToolFindsNothingInAnyFixture() throws Exception {
        var scan = new ScanKind().create("Scan", Map.of(), Path.of("."));
        for (Path folder : fixtureFolders()) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String result = scan.execute(Map.of("text", Files.readString(file)));
                    assertThat(JSON.readTree(result).get("count").asInt()).as(folder.getFileName() + "/" + file.getFileName() + ": " + result).isZero();
                }
            }
        }
    }
}
