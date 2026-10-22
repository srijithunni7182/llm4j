package io.github.llm4j.loom.explain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import org.junit.jupiter.api.Test;

class StoppedExplainTest {

    private static String explain(String source) {
        return ScriptExplainer.explain(
                new LoomParser(new Lexer(source).tokenize()).parseScript(), "t.loom", null);
    }

    @Test
    void aHandlerIsExplainedInWords() {
        String text =
                explain(
                        """
                agent A { model: "m" steerable: true }
                intervention { timeout: retry }
                workflow W() {
                    checkpoint c
                    delegate "go" to A -> out on_stopped { alt (_stop.reason == "user") { rewind to c at most 2 times side effects: keep } else { use partial } }
                }
                """);
        assertThat(text)
                .contains("If someone stops it:")
                .contains("Go back to c, at most 2 times.")
                .contains("Keep what the agent had so far")
                .contains("a person may steer it while it works (slowly: it does not stream)")
                .contains("A person may steer, while they work: A.")
                .contains("stopped for timeout: retry");
    }
}
