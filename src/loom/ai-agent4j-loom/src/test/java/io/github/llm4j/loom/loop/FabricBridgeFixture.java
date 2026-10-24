package io.github.llm4j.loom.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** A store with one triage run in it, laid out as a project the way a developer has it. */
final class FabricBridgeFixture {

    final Path repo;
    final Path store;
    final Path script;

    FabricBridgeFixture(Path dir, String message) throws Exception {
        repo = dir.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        store = dir.resolve("store");
        script = Runs.project(repo.resolve("proj"), Runs.TRIAGE);
        var body = new ObjectMapper().createObjectNode();
        body.putObject("input").put("message", message);
        body.putArray("humans").add("yes");
        var models = body.putObject("models");
        models.putObject("Intent").put("kind", "order_status").put("urgent", "no");
        models.put("Replier", "Your parcel shipped yesterday.");
        Runs.run(store, "r1", script, body, Map.of("message", message));
    }
}
