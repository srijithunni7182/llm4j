package io.github.llm4j.loom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * The record of the last judge validation, kept beside the dataset as {@code
 * eval/judge-validation.json}. A report says the judge was validated only when this names the
 * judge model it used and shows that the judge agreed with the people.
 */
public final class JudgeValidationRecord {

    static final String FILE = "judge-validation.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private JudgeValidationRecord() {}

    /** Writes the record, replacing an earlier one. */
    public static void write(Path evalDir, String model, JudgeValidation.Result r) throws IOException {
        ObjectNode n = JSON.createObjectNode();
        n.put("model", model);
        n.put("at", Instant.now().toString());
        n.put("outputs", r.outputs());
        n.put("interHumanKappa", r.interHumanKappa());
        if (r.judgeKappa() != null) n.put("judgeKappa", r.judgeKappa());
        n.put("meanBias", r.meanBias());
        n.put("usableForGating", r.usableForGating());
        Files.createDirectories(evalDir);
        Files.writeString(evalDir.resolve(FILE), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n) + "\n");
    }

    /** Whether a usable validation of exactly this judge model is on record. */
    public static boolean validatedFor(Path evalDir, String model) {
        if (model == null) return false;
        Path file = evalDir.resolve(FILE);
        if (!Files.isRegularFile(file)) return false;
        try {
            JsonNode n = JSON.readTree(Files.readString(file));
            return n != null
                    && model.equals(n.path("model").asText(null))
                    && n.path("usableForGating").asBoolean(false);
        } catch (IOException | RuntimeException e) {
            return false; // a record nobody can read validates nothing
        }
    }
}
