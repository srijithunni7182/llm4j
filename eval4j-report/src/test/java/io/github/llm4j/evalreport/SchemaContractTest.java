package io.github.llm4j.evalreport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The published JSON Schemas are the contract: examples and freshly exported bundles must satisfy
 * them (TST-01/02).
 */
public class SchemaContractTest {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    static JsonSchema schema(String name) throws IOException {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(
                        Files.newInputStream(
                                Path.of("src/test/resources/schema/" + name + ".schema.json")));
    }

    public static List<String> violations(Path bundle) throws IOException {
        List<String> problems = new ArrayList<>();
        check(
                problems,
                schema("run"),
                MAPPER.readTree(bundle.resolve("run.json").toFile()),
                "run.json");
        for (String[] f :
                new String[][] {
                    {"evaluations", "evaluation"},
                    {"scenarios", "scenario"},
                    {"tests", "test"},
                    {"traces", "trace"},
                    {"optimizations", "optimization"}
                }) {
            Path file = bundle.resolve(f[0] + ".jsonl");
            if (!Files.exists(file)) {
                continue;
            }
            JsonSchema s = schema(f[1]);
            int n = 0;
            for (String line : Files.readAllLines(file)) {
                n++;
                check(problems, s, MAPPER.readTree(line), f[0] + ".jsonl:" + n);
            }
        }
        return problems;
    }

    private static void check(List<String> problems, JsonSchema s, JsonNode node, String where) {
        Set<ValidationMessage> msgs = s.validate(node);
        msgs.forEach(m -> problems.add(where + ": " + m.getMessage()));
    }

    @Test
    void exampleBundlesSatisfyTheSchemas() throws Exception {
        for (String run : new String[] {"previous", "candidate"}) {
            assertThat(violations(Path.of("src/test/resources/examples/minimal/" + run)))
                    .as(run)
                    .isEmpty();
        }
    }

    @Test
    void aBundleExportedByEval4jSatisfiesTheSchemasAndRenders(@TempDir Path root) throws Exception {
        Path runDir = EndToEndTest.exportSampleRun(root);
        assertThat(violations(runDir)).isEmpty();
    }

    @Test
    void theDocumentationSampleSatisfiesTheSchemas() throws Exception {
        try (var runs = Files.list(Path.of("docs/sample/bundles/runs"))) {
            for (Path run : (Iterable<Path>) runs::iterator) {
                assertThat(violations(run)).as(run.getFileName().toString()).isEmpty();
            }
        }
    }
}
