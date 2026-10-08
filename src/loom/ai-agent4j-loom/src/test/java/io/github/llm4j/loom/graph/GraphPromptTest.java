package io.github.llm4j.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.prompt.PromptSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R7.1 of loom-prompt-files: the graph shows which prompt each agent runs, and only when there is one. */
class GraphPromptTest {

    @TempDir Path dir;

    private final GraphService service = new GraphService();

    private Path script(String source) throws IOException {
        return Files.writeString(dir.resolve("main.loom"), source);
    }

    private void prompt(String relative, String text) throws IOException {
        Path f = dir.resolve("prompts").resolve(relative);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    private static GraphNode first(GraphResult r, String agent) {
        return r.workflows().get(0).nodes().stream().filter(n -> agent.equals(n.agent())).findFirst().orElseThrow();
    }

    private static final String SCRIPT = """
            agent Researcher { model: "m" prompt: "researcher" }
            agent Plain { model: "m" system: "s" }
            workflow Main() {
                delegate "a" to Researcher -> a
                delegate "b" to Plain -> b
            }
            """;

    @Test
    void aStepAndItsAgentShowTheVersionThatWouldRun() throws IOException {
        prompt("researcher/v1.md", "old");
        prompt("researcher/v2.md", "new");

        GraphResult result = service.graph(script(SCRIPT));

        assertThat(first(result, "Researcher").attrs()).containsEntry("prompt", "researcher@v2");
        var info = result.agents().stream().filter(a -> a.name().equals("Researcher")).findFirst().orElseThrow().prompt();
        assertThat(info.version()).isEqualTo("v2");
        assertThat(info.file()).endsWith("prompts/researcher/v2.md".replace('/', java.io.File.separatorChar));
    }

    @Test
    void anAgentWithAnInlinePromptShowsNone() throws IOException {
        prompt("researcher.md", "x");

        GraphResult result = service.graph(script(SCRIPT));

        assertThat(first(result, "Plain").attrs()).doesNotContainKey("prompt");
        assertThat(result.agents().stream().filter(a -> a.name().equals("Plain")).findFirst().orElseThrow().prompt()).isNull();
    }

    @Test
    void aPinChangesTheVersionShown() throws IOException {
        prompt("researcher/v1.md", "old");
        prompt("researcher/v2.md", "new");

        GraphResult result = service.graph(script(SCRIPT), new PromptSettings(null, Map.of("researcher", "v1")));

        assertThat(first(result, "Researcher").attrs()).containsEntry("prompt", "researcher@v1");
    }

    @Test
    void aPromptThatIsNotThereIsShownAsWrittenWithNoFile() throws IOException {
        Files.createDirectories(dir.resolve("prompts"));

        GraphResult result = service.graph(script(SCRIPT));

        assertThat(first(result, "Researcher").attrs()).containsEntry("prompt", "researcher");
        var info = result.agents().stream().filter(a -> a.name().equals("Researcher")).findFirst().orElseThrow().prompt();
        assertThat(info.version()).isNull();
        assertThat(info.file()).isNull();
    }

    @Test
    void anImportedAgentsPromptIsFoundInTheEntryScriptsFolder() throws IOException {
        Files.writeString(dir.resolve("lib.loom"), "agent Lib { model: \"m\" prompt: \"libby\" }\nworkflow Helper() { delegate \"x\" to Lib -> r }\n");
        prompt("libby.md", "from the entry folder");
        Path main = script("import \"lib.loom\"\nagent A { model: \"m\" system: \"s\" }\nworkflow Main() { call Helper() -> out }\n");

        GraphResult result = service.graph(main);

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.agents()).extracting(AgentInfo::name).contains("Lib");
        var lib = result.agents().stream().filter(a -> a.name().equals("Lib")).findFirst().orElseThrow();
        assertThat(lib.prompt().version()).isEqualTo("v1");
    }

    @Test
    void jsonCarriesThePromptAndNeverItsText() throws IOException {
        prompt("researcher.md", "TOP SECRET WORDING");

        String json = GraphJson.write(service.graph(script(SCRIPT)));

        assertThat(json).contains("\"prompt\": \"researcher@v1\"").contains("\"ref\": \"researcher\"").contains("\"version\": \"v1\"").doesNotContain("TOP SECRET");
    }

    @Test
    void aScriptWithNoPromptFilesGivesExactlyTheOutputItAlwaysDid() throws IOException {
        Path main = script("agent A { model: \"m\" system: \"s\" }\nworkflow Main() { delegate \"x\" to A -> r }\n");
        Files.createDirectories(dir.resolve("prompts")); // a folder with nothing in it changes nothing

        String json = GraphJson.write(service.graph(main));

        assertThat(json).doesNotContain("prompt");
    }
}
