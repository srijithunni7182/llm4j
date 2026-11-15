package io.github.llm4j.tools.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * What this module adds to an application (phase 36, D2): the core, the light tools module, Jackson
 * (with its YAML and time modules, all at the repository's one version) and the OpenAPI parser, which brings its own tree (about forty jars, among them guava, Apache
 * HttpClient and Rhino, through its Swagger 2 converter). All Apache 2.0 or compatible. Maven writes
 * the list before the tests run.
 */
class WeightTest {

    static final Set<String> ALLOWED =
            Set.of(
                    "io.github.srijithunni7182:ai-agent4j:compile",
                    "io.github.srijithunni7182:ai-agent4j-tools:compile",
                    "com.fasterxml.jackson.core:jackson-databind:compile",
                    "io.swagger.parser.v3:swagger-parser:compile",
                    // the parser's own Jackson modules are older than the repository's one version, so they are
                    // excluded and these two are declared at jackson.version
                    "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:compile",
                    "com.fasterxml.jackson.datatype:jackson-datatype-jsr310:compile");

    @Test
    void theModuleDependsOnTheCoreTheLightToolsJacksonAndTheParserOnly() throws Exception {
        Path list = Path.of("target", "weight", "dependencies.txt");
        assertThat(list).isRegularFile();
        Set<String> shipped = new TreeSet<>();
        for (String raw : Files.readAllLines(list)) {
            String line = raw.replaceAll("\u001B\\[[0-9;]*m", "").strip();
            int note = line.indexOf(" -- ");
            if (note >= 0) line = line.substring(0, note).strip();
            String[] parts = line.split(":");
            if (parts.length < 5 || parts[parts.length - 1].startsWith("test")) continue;
            shipped.add(parts[0] + ":" + parts[1] + ":" + parts[parts.length - 1]);
        }
        assertThat(shipped).containsExactlyInAnyOrderElementsOf(ALLOWED);
    }
}
