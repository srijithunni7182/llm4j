package io.github.llm4j.loom.explain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.graph.GraphJson;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import org.junit.jupiter.api.Test;

/**
 * Phase 24, D39: the default stays {@code transcript: all}, and is made visible. {@code weave
 * explain} and the graph say what each agent sees of what was said before it.
 */
class TranscriptVisibleTest {

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;

    private static final String SOURCE =
            """
            shared_prompt: "House voice."
            agent Everyone { model: "m" }
            agent Mine { model: "m" memory { transcript: own } }
            agent Blank { model: "m" memory { transcript: none } shared_prompt: none }
            workflow Main() {
                delegate "a" to Everyone -> x
                delegate "b" to Mine -> y
                delegate "c" to Blank -> z
            }
            """;

    private static LoomScript script() {
        return new LoomParser(new Lexer(SOURCE).tokenize()).parseScript();
    }

    @Test
    void explainMarksEachAgentThatSeesEverythingSaidBeforeIt() {
        String text = ScriptExplainer.explain(script(), "x.loom", null);

        assertThat(text).contains("Everyone: ").contains("sees everything said before it");
        assertThat(text).contains("Mine: ").contains("sees only its own earlier exchanges");
        assertThat(text).contains("Blank: ").contains("sees nothing said before it");
        assertThat(text).contains("is left out of the shared prompt");
    }

    @Test
    void theGraphJsonCarriesTheModeOfEveryAgent() throws Exception {
        java.nio.file.Path file = dir.resolve("x.loom");
        java.nio.file.Files.writeString(file, SOURCE);
        var json =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(
                                GraphJson.write(
                                        new io.github.llm4j.loom.graph.GraphService()
                                                .graph(file)));
        var agents = json.get("agents");

        assertThat(agents.get(0).get("transcript").asText()).isEqualTo("all");
        assertThat(agents.get(1).get("transcript").asText()).isEqualTo("own");
        assertThat(agents.get(2).get("transcript").asText()).isEqualTo("none");
        assertThat(agents.get(2).get("sharedPromptExcluded").asBoolean()).isTrue();
        assertThat(agents.get(0).has("sharedPromptExcluded")).isFalse();
    }
}
