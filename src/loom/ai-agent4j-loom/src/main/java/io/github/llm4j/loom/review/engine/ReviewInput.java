package io.github.llm4j.loom.review.engine;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.graph.GraphResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Everything a review reads, gathered once and handed to every source of findings. Nothing here is
 * run: no model, tool or task is started, and nothing is read from the network.
 *
 * @param file the script's path as shown in findings
 * @param baseDir the folder the script is in (prompt files and settings are found from it)
 * @param script the parsed script
 * @param lootReach what a {@code .loot} file says tools reach ({@code Name.reach = reads})
 * @param problems what the script's own validation reported
 * @param graph the workflow graphs (the ids of {@code weave graph}), or {@code null} when not built
 */
public record ReviewInput(
        String file,
        Path baseDir,
        LoomScript script,
        Map<String, String> lootReach,
        List<ValidityProblem> problems,
        GraphResult graph) {

    public ReviewInput {
        lootReach = lootReach == null ? Map.of() : Map.copyOf(lootReach);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }
}
