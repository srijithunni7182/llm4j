package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Values put into a payload are never rewritten by other variables' names (found while testing guards:
 * an error message saying "task" had the task's text — with its PII — put back into it).
 */
class PayloadSubstitutionTest {

    @TempDir
    Path dir;

    static final String SCRIPT = """
            agent A { model: "m" }
            workflow Main() { delegate "%s" to A -> out }
            """;

    String sent(String payload, Map<String, String> vars) {
        Harness h = new Harness(dir);
        h.ready(SCRIPT.formatted(payload)).executeWorkflow("Main", vars);
        String task = h.task(0);
        return task.substring(task.indexOf("Question: ") + 10).strip();
    }

    @Test
    void insertedValuesAreNotRescanned() {
        assertThat(sent("{note}", Map.of("user", "Asha", "note", "Tell the user hi")))
                .endsWith("Tell the user hi");
        assertThat(sent("{alpha} and {beta}", Map.of("alpha", "beta-side", "beta", "second")))
                .endsWith("beta-side and second");
    }

    @Test
    void bareNamesInTheScriptsOwnTextStillWork() {
        assertThat(sent("Greet user by name", Map.of("user", "Asha"))).endsWith("Greet Asha by name");
        // one pass, longest first: a replacement is not replaced again
        assertThat(sent("topic then top", Map.of("topic", "top", "top", "bottom"))).endsWith("top then bottom");
    }

    @Test
    void unknownPlaceholdersAndMissingFieldsBehaveAsBefore() {
        assertThat(sent("{unknown} stays", Map.of())).endsWith("{unknown} stays");
        assertThat(sent("[{report.missing}]", Map.of("report", "x"))).endsWith("[]");
    }
}
