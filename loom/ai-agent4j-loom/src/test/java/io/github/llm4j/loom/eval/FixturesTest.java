package io.github.llm4j.loom.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R3.9 of loom-weave-eval: tools that answer from recorded text, and nothing reaches the outside world in a mock run. */
class FixturesTest {

    @TempDir Path dir;

    private static LoomScript script() {
        return new LoomParser(new Lexer("""
                tool Search { use: serpapi  api_key: env.SERPAPI_KEY }
                tool Notify { use: webhook  url: env.HOOK }
                agent A { model: "m" system: "s" tools: [Search, Notify, calculator] }
                workflow Main() { delegate "x" to A -> r }
                """).tokenize()).parseScript();
    }

    private void write(String yaml) throws IOException {
        Files.writeString(dir.resolve("fixtures.yaml"), yaml);
    }

    @Test
    void noFileMeansNoFixtures() {
        assertThat(Fixtures.read(dir).names()).isEmpty();
    }

    @Test
    void aMappingOfToolNamesToEntriesIsRead() throws IOException {
        write("""
                Search:
                  - match: flights? to paris
                    snippets: ["Flights to Paris start at 80 euros."]
                Other:
                  - match: x
                    snippets: [y]
                """);

        assertThat(Fixtures.read(dir).names()).containsExactly("Search", "Other");
    }

    @Test
    void aFileThatIsNotAMappingOrHasANonListToolIsRefusedWithWhatToWrite() throws IOException {
        write("- just: a list\n");
        assertThatThrownBy(() -> Fixtures.read(dir)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mapping of tool name to entries");

        write("Search: not-a-list\n");
        assertThatThrownBy(() -> Fixtures.read(dir)).hasMessageContaining("Search should be a list of entries");

        write("Search: [unclosed\n");
        assertThatThrownBy(() -> Fixtures.read(dir)).hasMessageContaining("cannot be read");
    }

    @Test
    void aFixtureToolReplacesTheScriptsDeclarationOfThatTool() throws Exception {
        write("Search:\n  - match: paris\n    snippets: [\"Paris is sunny\"]\n");
        LoomScript script = script();
        ToolRegistry registry = new ToolRegistry();

        var replaced = Fixtures.read(dir).apply(script, registry, false);

        assertThat(replaced).containsExactly("Search");
        assertThat(script.getTools()).extracting(t -> t.getName()).containsExactly("Notify");
        assertThat(registry.getTool("Search").execute(Map.of("query", "weather in Paris"))).contains("Paris is sunny");
        assertThat(registry.getTool("Search").execute(Map.of("query", "weather in Rome"))).contains("No results found");
    }

    @Test
    void inAMockRunEveryToolTheAgentsUseIsAnsweredWithoutTheOutsideWorld() throws Exception {
        LoomScript script = script();
        ToolRegistry registry = new ToolRegistry();

        var replaced = Fixtures.none().apply(script, registry, true);

        assertThat(replaced).containsExactlyInAnyOrder("Search", "Notify", "calculator");
        assertThat(script.getTools()).isEmpty();
        assertThat(registry.getTool("Notify").execute(Map.of("query", "anything"))).contains(Fixtures.MOCK_RESULT);
        assertThat(registry.getTool("calculator").execute(Map.of("query", "2+2"))).contains(Fixtures.MOCK_RESULT);
    }

    @Test
    void inARealRunToolsWithoutFixturesKeepTheirOwnDeclaration() {
        LoomScript script = script();

        var replaced = Fixtures.none().apply(script, new ToolRegistry(), false);

        assertThat(replaced).isEmpty();
        assertThat(script.getTools()).hasSize(2);
    }
}
