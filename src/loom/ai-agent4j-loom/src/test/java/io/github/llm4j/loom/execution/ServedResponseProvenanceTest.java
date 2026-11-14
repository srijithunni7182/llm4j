package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import org.junit.jupiter.api.Test;

/**
 * An http tool that {@code reaches:} a sandbox calls a program the model may have written, so what
 * it answers is the model's text, not the tool's own data (review of #122, finding 3).
 */
class ServedResponseProvenanceTest {

    static final String TOOLS =
            """
            tool Data { use: workspace  root: "work"  allow: "**"  mode: readwrite }
            tool Box  { use: sandbox  workspace: Data  isolation: process  network: open  allow: "python3"  unattended: true }
            tool Api  { use: http  reaches: Box }
            tool Web  { use: http  base_url: "https://api.example.com" }
            workflow Main() { note "x" }
            """;

    HarnessExecutor executor() {
        LoomScript script = new LoomParser(new Lexer(TOOLS).tokenize()).parseScript();
        return new HarnessExecutor(script, new ToolRegistry(), name -> null);
    }

    @Test
    void whatAServedProgramAnswersIsUncheckedModelText() {
        assertThat(executor().useResultTag("Api", Provenance.NOT_MODEL))
                .isEqualTo(Provenance.UNCHECKED);
    }

    @Test
    void anOrdinaryApiAnswerStaysTheToolsOwnData() {
        assertThat(executor().useResultTag("Web", Provenance.NOT_MODEL))
                .isEqualTo(Provenance.NOT_MODEL);
    }
}
