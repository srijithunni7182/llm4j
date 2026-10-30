package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where a project stands with {@code weave preflight}, read from {@code .loom/preflight.json}: a
 * result is current only if it was a real run (not {@code --mock}) of this script as it is now.
 */
record PreflightState(String state, String at, String detail) {

    static final String NONE = "none";
    static final String MOCK_ONLY = "mock-only";
    static final String STALE = "stale";
    static final String FAILING = "failing";
    static final String DONE = "done";

    static PreflightState read(Path projectRoot, Path script) {
        Path record = projectRoot.resolve(".loom").resolve("preflight.json");
        if (!Files.isRegularFile(record)) return new PreflightState(NONE, null, "");
        try {
            JsonNode json = new ObjectMapper().readTree(record.toFile());
            String at = json.path("at").asText(null);
            if (json.path("mock").asBoolean(false)) return new PreflightState(MOCK_ONLY, at, "");
            String now = PreflightCommand.hash(Files.readString(script));
            if (!now.equals(json.path("scriptHash").asText())) return new PreflightState(STALE, at, "");
            for (JsonNode c : json.path("checks"))
                if (c.path("result").asText().equals("fail"))
                    return new PreflightState(FAILING, at, c.path("detail").asText(""));
            return new PreflightState(DONE, at, "");
        } catch (IOException | RuntimeException e) {
            return new PreflightState(NONE, null, "");
        }
    }
}
