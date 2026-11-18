package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The stages a person chose to skip, each with their reason, who and when, kept in {@code
 * .loom/skipped.json}. A skip is a recorded, informed choice: the journey shows it and the Guide
 * reads the list back before going live. A file that cannot be read skips nothing (fail closed).
 */
final class SkippedStages {

    /** One recorded skip. */
    record Skip(String stage, String because, String by, String at) {
        Skip {
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(because, "because");
            Objects.requireNonNull(by, "by");
            Objects.requireNonNull(at, "at");
        }
    }

    /** What was read: the skips by stage id, and a warning when the file could not be read. */
    record Read(Map<String, Skip> skips, Optional<String> warning) {
        Read {
            skips = Map.copyOf(skips);
        }
    }

    static final String FILE = "skipped.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private SkippedStages() {}

    static Path file(Path projectRoot) {
        return projectRoot.resolve(".loom").resolve(FILE);
    }

    static Read read(Path projectRoot) {
        Path f = file(projectRoot);
        if (!Files.isRegularFile(f)) return new Read(Map.of(), Optional.empty());
        try {
            JsonNode root = JSON.readTree(f.toFile());
            Map<String, Skip> out = new LinkedHashMap<>();
            for (JsonNode s : root.path("skips")) {
                String stage = s.path("stage").asText("");
                String because = s.path("because").asText("");
                if (stage.isBlank() || because.isBlank())
                    return unreadable(f, "an entry has no stage or no reason");
                // a hand-edited file can hold anything: what reaches the journey and the Guide is bounded
                out.put(stage, new Skip(stage, bound(because), bound(s.path("by").asText("")), bound(s.path("at").asText(""))));
            }
            return new Read(out, Optional.empty());
        } catch (IOException e) {
            return unreadable(f, "it is not valid JSON");
        }
    }

    static final int MAX_TEXT = 300;

    private static String bound(String s) {
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "…";
    }

    private static Read unreadable(Path f, String why) {
        return new Read(
                Map.of(),
                Optional.of(
                        ".loom/"
                                + FILE
                                + " could not be read ("
                                + why
                                + "), so no stage is treated as skipped. Fix or delete "
                                + f.getFileName()
                                + " and record the skips again with weave next --skip."));
    }

    /** Records (or replaces) the skip of one stage. */
    static void skip(Path projectRoot, String stage, String because, String by, Instant at) throws IOException {
        Map<String, Skip> skips = new LinkedHashMap<>(readOrFail(projectRoot));
        skips.put(stage, new Skip(stage, because.strip(), by, at.toString()));
        write(projectRoot, skips);
    }

    /** Removes the skip of one stage; false when it was not skipped. */
    static boolean unskip(Path projectRoot, String stage) throws IOException {
        Map<String, Skip> skips = new LinkedHashMap<>(readOrFail(projectRoot));
        if (skips.remove(stage) == null) return false;
        write(projectRoot, skips);
        return true;
    }

    private static Map<String, Skip> readOrFail(Path projectRoot) throws IOException {
        Read r = read(projectRoot);
        if (r.warning().isPresent()) throw new IOException(r.warning().get());
        return r.skips();
    }

    private static void write(Path projectRoot, Map<String, Skip> skips) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode list = root.putArray("skips");
        for (Skip s : skips.values()) {
            ObjectNode n = list.addObject();
            n.put("stage", s.stage());
            n.put("because", s.because());
            n.put("by", s.by());
            n.put("at", s.at());
        }
        Path f = file(projectRoot);
        Files.createDirectories(f.getParent());
        Files.writeString(f, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
    }
}
