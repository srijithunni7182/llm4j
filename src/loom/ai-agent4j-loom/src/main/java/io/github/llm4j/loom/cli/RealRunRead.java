package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/**
 * Whether a person has read one real answer of this script and said what is right and wrong with
 * it, kept in {@code .loom/confirmed.json}. It holds for the script as it was then: an edited
 * script needs its answer read again.
 */
final class RealRunRead {

    static final String STEP = "real-run";

    private RealRunRead() {}

    static boolean confirmed(Path projectRoot, Path script) {
        Path record = projectRoot.resolve(".loom").resolve("confirmed.json");
        if (!Files.isRegularFile(record)) return false;
        try {
            JsonNode step = new ObjectMapper().readTree(record.toFile()).path(STEP);
            return !step.isMissingNode()
                    && PreflightCommand.hash(Files.readString(script))
                            .equals(step.path("scriptHash").asText());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static void confirm(Path projectRoot, Path script, Clock clock) throws IOException {
        Path folder = Files.createDirectories(projectRoot.resolve(".loom"));
        ObjectMapper mapper = new ObjectMapper();
        var json = mapper.createObjectNode();
        json.putObject(STEP)
                .put("at", clock.instant().toString())
                .put("scriptHash", PreflightCommand.hash(Files.readString(script)));
        Files.writeString(folder.resolve("confirmed.json"), mapper.writeValueAsString(json));
    }
}
