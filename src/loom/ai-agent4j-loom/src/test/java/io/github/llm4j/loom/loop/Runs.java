package io.github.llm4j.loom.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.llm4j.loom.travel.ScriptHash;
import io.github.llm4j.loom.wtest.CaseProbe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Real run folders for tests: a script is run with scripted collaborators, and the journal it
 * leaves is written beside a {@code run.json}, exactly as a run of {@code weave run} leaves them.
 */
final class Runs {

    private Runs() {}

    static final String TRIAGE =
            """
            agent Replier { model: "gemini-2.5-flash"  system: "Answer the customer." }
            agent Escalator { model: "gemini-2.5-flash"  system: "Summarise the case." }
            intentclassifier Intent {
                model: "gemini-2.5-flash"
                choose kind: order_status | refund | other
                ask urgent: "Is the customer threatening to leave?"
            }
            workflow Main(message) {
                intentclassify Intent on message -> intent
                alt (intent.kind == "order_status") {
                    delegate "Reply to: {message}" to Replier -> reply
                    human_prompt "Send this reply? (yes/no)" -> ok
                    return reply
                } else {
                    delegate "Summarise: {message}" to Escalator -> summary
                    return summary
                }
            }
            """;

    /** A project folder with {@code main.loom}. */
    static Path project(Path dir, String script) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("main.loom"), script);
        return dir.resolve("main.loom");
    }

    /**
     * Runs {@code script} with the scripted case body (YAML) and leaves the run in {@code
     * store/runs/<id>}.
     */
    static Path run(Path store, String id, Path script, String caseYaml, Map<String, String> inputs)
            throws Exception {
        return run(store, id, script, (ObjectNode) new YAMLMapper().readTree(caseYaml), inputs);
    }

    static Path run(Path store, String id, Path script, ObjectNode body, Map<String, String> inputs)
            throws Exception {
        body.put("name", "x");
        var result = CaseProbe.run(script, "Main", body);
        if (!result.problems().isEmpty())
            throw new IllegalStateException(String.join("; ", result.problems()));
        Path dir = store.resolve("runs").resolve(id);
        Files.createDirectories(dir);
        ObjectMapper json = new ObjectMapper();
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("script", script.toAbsolutePath().toString());
        spec.put("workflow", "Main");
        spec.put("inputs", inputs);
        spec.put("scriptHash", ScriptHash.of(script));
        json.writeValue(dir.resolve("run.json").toFile(), spec);
        json.writeValue(dir.resolve("journal.json").toFile(), result.journal());
        return dir;
    }
}
