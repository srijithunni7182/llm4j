package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 36e, D11, point 4: the new kinds act through their profile, not through a list of names in the
 * test runner. An {@code openapi} operation that changes, a {@code command} and an {@code http} write
 * record their effects in a case; an {@code openapi} read does not.
 */
class IntegrationEffectsInTestsTest {

    @TempDir Path dir;

    static final String SCRIPT =
            """
            tool Pet_add { use: openapi  spec: "pets.json"  operation: "addPet"  effect: changes }
            tool Pet_get { use: openapi  spec: "pets.json"  operation: "getPet"  effect: reads }
            tool Img { use: command  isolation: process  network: open  workspace: Data  run: "printf %s {text}"  param.text: "string" }
            tool Data { use: workspace  root: "work"  allow: "**"  mode: readwrite }
            workflow Main(id) {
                use Pet_get(petId = "1") -> got
                use Pet_add(name = "Rex") -> added
                use Img(text = "x") -> drawn
                return added
            }
            """;

    private TestRunner.CaseResult run(String expectations) throws Exception {
        Files.createDirectories(dir.resolve("work"));
        String yaml = "script: ../main.loom\nworkflow: Main\ncases:\n  - name: acts\n    input: { id: '1' }\n    tools: { Pet_get: 'a pet', Pet_add: 'added' }\n    expect:\n" + expectations;
        List<TestRunner.CaseResult> results = Wtest.run(dir, SCRIPT, yaml);
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    @Test
    void aChangingOperationAndACommandRecordTheirEffectsAndAReadDoesNot() throws Exception {
        TestRunner.CaseResult r =
                run("      effects:\n        - { tool: Pet_add, kind: openapi }\n        - { tool: Img, kind: command }\n");

        assertThat(r.failures()).isEmpty();
    }

    @Test
    void aReadOperationIsNotAnEffect() throws Exception {
        TestRunner.CaseResult r = run("      effects:\n        - { tool: Pet_get, kind: openapi }\n");

        assertThat(r.failures()).as("Pet_get reads, so it records nothing").isNotEmpty();
    }
}
