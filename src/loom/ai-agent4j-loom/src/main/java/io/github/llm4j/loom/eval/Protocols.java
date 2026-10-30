package io.github.llm4j.loom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The run protocols of a project, read from {@code eval-protocol.yaml} at its root: a core set on
 * every change, the validation set nightly, validation plus held-out at a release gate, and a
 * drift check at a fixed configuration on a schedule whatever changed. A protocol is a set of
 * {@code weave eval} options, so what runs is never a separate mechanism; {@code schedule} and
 * {@code pinned} are for the people (and the CI file) that run it. The numbers are the team's.
 */
public final class Protocols {

    /** The file at the project root. */
    public static final String FILE = "eval-protocol.yaml";

    private static final List<String> KEYS =
            List.of("split", "repetitions", "release-gate", "judge-samples", "max-cost", "max-tokens", "schedule", "pinned");
    private static final int MAX_REPETITIONS = 10;

    private Protocols() {}

    /**
     * One named protocol; a null or false field is not set.
     *
     * @param split run one split
     * @param repetitions runs per case, 1 to 10
     * @param releaseGate whether it is a release gate (the only way held-out cases run)
     * @param judgeSamples judge samples per rubric line
     * @param maxCost a cost cap, as written
     * @param maxTokens a token cap
     * @param schedule when it runs, for the people ({@code weekly}, {@code nightly}, ...)
     * @param pinned whether the configuration must not change between runs
     */
    public record Protocol(
            String split,
            Integer repetitions,
            boolean releaseGate,
            Integer judgeSamples,
            String maxCost,
            Long maxTokens,
            String schedule,
            boolean pinned) {}

    /**
     * The protocols of a project, in the order written; empty when there is no file.
     *
     * @throws IllegalArgumentException for a key that is not one, a split that is not a split, a
     *     repetition count out of range, or a protocol that reads held-out cases without saying it
     *     is a release gate: each says which protocol and what to write
     */
    public static Map<String, Protocol> load(Path project) throws IOException {
        Path file = project.resolve(FILE);
        if (!Files.isRegularFile(file)) return Map.of();
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(file));
        if (root == null || !root.has("protocols") || !root.get("protocols").isObject())
            throw new IllegalArgumentException(FILE + " needs a map named protocols: nightly: { split: validation, repetitions: 5 }");
        Map<String, Protocol> out = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.get("protocols").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            out.put(e.getKey(), read(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static Protocol read(String name, JsonNode n) {
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!KEYS.contains(key))
                throw new IllegalArgumentException("protocol " + name + ": '" + key + "' is not a setting (the settings are " + String.join(", ", KEYS) + ")");
        }
        String split = n.hasNonNull("split") ? n.get("split").asText() : null;
        if (split != null && !Splits.ALL.contains(split))
            throw new IllegalArgumentException("protocol " + name + ": split '" + split + "' is not one of " + String.join(", ", Splits.ALL));
        Integer repetitions = n.hasNonNull("repetitions") ? n.get("repetitions").asInt() : null;
        if (repetitions != null && (repetitions < 1 || repetitions > MAX_REPETITIONS))
            throw new IllegalArgumentException("protocol " + name + ": repetitions is 1 to " + MAX_REPETITIONS + ", not " + repetitions);
        boolean gate = n.path("release-gate").asBoolean(false);
        if (Splits.HELD_OUT.equals(split) && !gate)
            throw new IllegalArgumentException("protocol " + name + ": split heldout runs only at a release gate; add release-gate: true");
        return new Protocol(
                split,
                repetitions,
                gate,
                n.hasNonNull("judge-samples") ? n.get("judge-samples").asInt() : null,
                n.hasNonNull("max-cost") ? n.get("max-cost").asText() : null,
                n.hasNonNull("max-tokens") ? n.get("max-tokens").asLong() : null,
                n.hasNonNull("schedule") ? n.get("schedule").asText() : null,
                n.path("pinned").asBoolean(false));
    }
}
