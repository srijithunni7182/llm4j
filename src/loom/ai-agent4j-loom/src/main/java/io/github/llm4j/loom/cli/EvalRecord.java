package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The last evaluation of a project, kept in {@code .loom/eval.json}: whether it ran on a real
 * model or a mock, against which dataset version and which script. {@code weave next} reads it to
 * tell a real evaluation that still holds from one made for an older script or dataset.
 */
record EvalRecord(String mode, String datasetVersion, String scriptHash) {

    static final String REAL = "real";

    static void write(Path projectRoot, String mode, String datasetVersion, String scriptHash)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        var json = mapper.createObjectNode();
        json.put("mode", mode).put("datasetVersion", datasetVersion).put("scriptHash", scriptHash);
        Files.writeString(
                Files.createDirectories(projectRoot.resolve(".loom")).resolve("eval.json"),
                mapper.writeValueAsString(json));
    }

    static Optional<EvalRecord> read(Path projectRoot) {
        Path file = projectRoot.resolve(".loom").resolve("eval.json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            JsonNode json = new ObjectMapper().readTree(file.toFile());
            return Optional.of(
                    new EvalRecord(
                            json.path("mode").asText(""),
                            json.path("datasetVersion").asText(""),
                            json.path("scriptHash").asText("")));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
