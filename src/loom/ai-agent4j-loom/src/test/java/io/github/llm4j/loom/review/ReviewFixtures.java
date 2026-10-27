package io.github.llm4j.loom.review;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.graph.GraphService;
import io.github.llm4j.loom.review.engine.ReviewInput;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Builds a {@link ReviewInput} from script text, the way the command does, for tests. */
final class ReviewFixtures {

    private ReviewFixtures() {}

    static ReviewInput input(Path dir, String source) {
        return input(dir, source, Map.of(), true);
    }

    static ReviewInput input(
            Path dir, String source, Map<String, String> lootReach, boolean withGraph) {
        try {
            Path file = dir.resolve("main.loom");
            Files.writeString(file, source);
            LoomScript script = new LoomLoader().load(file.toString());
            return new ReviewInput(
                    "main.loom",
                    dir,
                    script,
                    lootReach,
                    List.of(),
                    withGraph ? new GraphService().graph(file) : null);
        } catch (Exception e) {
            if (e instanceof IOException io) throw new UncheckedIOException(io);
            throw new IllegalStateException(e);
        }
    }
}
