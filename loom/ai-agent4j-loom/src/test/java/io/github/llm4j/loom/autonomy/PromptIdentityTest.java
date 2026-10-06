package io.github.llm4j.loom.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.prompt.MarkdownFolderPromptRegistry;
import io.github.llm4j.loom.prompt.PromptCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R4.1 of loom-prompt-files: an agent's prompt text is part of who it is, so a prompt edit or a different pin is a different agent. */
class PromptIdentityTest {

    @TempDir Path dir;

    private static final String SOURCE = """
            agent Triager { model: "m" prompt: "triage" }
            agent Other { model: "m" system: "other" }
            decision Refund { proposed by: Triager choices: approve, reject }
            workflow W() { decide Refund -> v }
            """;

    private void write(String relative, String text) throws Exception {
        Path f = dir.resolve("prompts").resolve(relative);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    private String identity(Map<String, String> pins) {
        var script = DecisionParseTest.parse(SOURCE);
        var catalog = new PromptCatalog(new MarkdownFolderPromptRegistry(dir.resolve("prompts")), pins);
        return AgentIdentity.of(script, script.getDecisions().get(0), dir, catalog);
    }

    @Test
    void editingThePromptFileMakesADifferentAgent() throws Exception {
        write("triage.md", "Be strict.");
        String before = identity(Map.of());

        write("triage.md", "Be lenient.");

        assertThat(identity(Map.of())).isNotEqualTo(before);
    }

    @Test
    void theSameTextGivesTheSameIdentityAndTouchingOtherPromptsDoesNot() throws Exception {
        write("triage.md", "Be strict.");
        write("unrelated.md", "one");
        String before = identity(Map.of());

        write("unrelated.md", "two");
        write("triage.md", "Be strict.");

        assertThat(identity(Map.of())).isEqualTo(before);
    }

    @Test
    void pinningAnotherVersionIsADifferentAgentAndPinningTheLatestIsNot() throws Exception {
        write("triage/v1.md", "old");
        write("triage/v2.md", "new");
        String latest = identity(Map.of());

        assertThat(identity(Map.of("triage", "v1"))).isNotEqualTo(latest);
        assertThat(identity(Map.of("triage", "v2"))).isEqualTo(latest);
    }

    @Test
    void aMissingPromptIsStillAnIdentityAndNotTheSameAsAPresentOne() throws Exception {
        String missing = identity(Map.of());
        write("triage.md", "now present");

        assertThat(missing).hasSize(64);
        assertThat(identity(Map.of())).isNotEqualTo(missing);
    }

    @Test
    void withoutACatalogTheIdentityIsWhatItWasBefore() {
        var script = DecisionParseTest.parse(SOURCE);

        assertThat(AgentIdentity.of(script, script.getDecisions().get(0), dir))
                .isEqualTo(AgentIdentity.of(script, script.getDecisions().get(0), dir, null));
    }
}
