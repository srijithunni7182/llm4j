package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * What the developer already knows, so the Guide teaches to the person: a level per concept in
 * the person's home folder (it travels between projects), and the same in the project folder for
 * what was discussed there. Local only; it is shown on request and can be reset.
 */
final class GuideLearner {

    /** The levels a concept can be at. */
    static final List<String> LEVELS = List.of("seen", "understood", "needs-more");

    /** The concepts the Guide tracks. */
    static final List<String> CONCEPTS =
            List.of(
                    "agents-and-roles", "harness", "golden-dataset", "judge", "rubric", "mock-vs-real",
                    "effects-and-approvals", "lethal-trifecta", "budgets-and-caps", "durability-and-journal",
                    "context-window", "secret-store", "observability", "evaluation-report", "debugging-from-journal");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path personal;
    private final Path project;
    private final Clock clock;

    GuideLearner(Path home, Path projectRoot, Clock clock) {
        this.personal = Objects.requireNonNull(home, "home").resolve(".loom").resolve("guide").resolve("learner.json");
        this.project = Objects.requireNonNull(projectRoot, "projectRoot").resolve(".loom").resolve("guide").resolve("learner.json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Records a concept's level in both records. */
    void record(String concept, String level) throws IOException {
        if (!CONCEPTS.contains(concept))
            throw new IllegalArgumentException("Unknown concept " + concept + ". Known: " + String.join(", ", CONCEPTS) + ".");
        if (!LEVELS.contains(level))
            throw new IllegalArgumentException("Unknown level " + level + ". Use one of: " + String.join(", ", LEVELS) + ".");
        for (Path f : List.of(personal, project)) {
            ObjectNode root = read(f);
            ObjectNode c = root.with("concepts").putObject(concept);
            c.put("level", level);
            c.put("at", clock.instant().toString());
            Files.createDirectories(f.getParent());
            Files.writeString(f, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        }
    }

    /** Both records, for the Guide to read at the start of a turn. */
    JsonNode both() {
        ObjectNode out = JSON.createObjectNode();
        out.set("person", read(personal).path("concepts"));
        out.set("thisProject", read(project).path("concepts"));
        out.putPOJO("concepts", CONCEPTS);
        return out;
    }

    /** Forgets everything recorded about the person and this project. */
    void reset() throws IOException {
        Files.deleteIfExists(personal);
        Files.deleteIfExists(project);
    }

    private static ObjectNode read(Path f) {
        try {
            if (Files.isRegularFile(f) && JSON.readTree(f.toFile()) instanceof ObjectNode o) return o;
        } catch (IOException e) {
            // an unreadable record is treated as empty: the Guide asks again rather than assume
        }
        return JSON.createObjectNode();
    }
}
