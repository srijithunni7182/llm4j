package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A script that reads {@code {x_citations}} for an agent that cannot produce them is told so. */
class CitationVariableCheckTest {
    private static List<String> lint(String source) {
        var script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        return script.getWorkflows().stream()
                .flatMap(w -> WorkflowLint.check(w, script).stream())
                .map(WorkflowLint.Finding::message)
                .toList();
    }

    private static final String READS =
            "workflow W() {\n  delegate \"q\" to R -> research\n  note \"Sources: {research_citations}\"\n}\n";

    @Test
    void anUngroundedAgentsCitationsAreNeverSet() {
        List<String> found = lint("agent R { model: \"m\" system: \"s\" }\n" + READS);

        assertThat(found)
                .anySatisfy(
                        m ->
                                assertThat(m)
                                        .contains("{research_citations} is never set")
                                        .contains("agent R is not grounded"));
    }

    @Test
    void aGroundedAgentsCitationsAreFine() {
        List<String> found =
                lint("agent R { model: \"m\" system: \"s\" grounding: web }\n" + READS);

        assertThat(found).noneMatch(m -> m.contains("_citations"));
    }

    @Test
    void aWorkflowThatNeverReadsCitationsIsNotTouched() {
        List<String> found =
                lint(
                        "agent R { model: \"m\" system: \"s\" }\n"
                                + "workflow W() {\n  delegate \"q\" to R -> research\n  note \"{research}\"\n}\n");

        assertThat(found).noneMatch(m -> m.contains("_citations"));
    }
}
