package io.github.llm4j.eval.optimize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.llm4j.eval.report.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Writes run state atomically after every round and reads it back on resume. A failed write leaves
 * the previous checkpoint intact; an unreadable checkpoint is reported, never silently ignored.
 */
final class Checkpointer {

    static final String FILE_NAME = "optimizer-checkpoint.json";

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;

    Checkpointer(Path directory) {
        this.file = directory.resolve(FILE_NAME);
    }

    void save(CheckpointState state) {
        try {
            AtomicFiles.write(file, MAPPER.writeValueAsBytes(state));
        } catch (IOException e) {
            throw new IllegalStateException("could not write checkpoint " + file, e);
        }
    }

    /** The saved state, or empty if there is no checkpoint. */
    Optional<CheckpointState> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(Files.readAllBytes(file), CheckpointState.class));
        } catch (IOException e) {
            throw new OptimizerConfigurationException(
                    "the checkpoint "
                            + file
                            + " could not be read ("
                            + e.getMessage()
                            + "). Delete it to start a fresh run, or restore it from a backup.",
                    e);
        }
    }
}
