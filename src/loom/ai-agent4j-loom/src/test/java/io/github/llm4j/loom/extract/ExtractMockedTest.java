package io.github.llm4j.loom.extract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Phase 24, R5: under {@code weave eval --mock} an extractor gets a well-formed value of its schema. */
class ExtractMockedTest {

    @Test
    void aMockModelAnswersAnExtractorWithAWellFormedValueOfItsSchema() {
        LoomScript script =
                new LoomParser(
                                new Lexer(
                                                """
                extractor Invoice {
                    model: "m"
                    fields { vendor: string, total: number, currency: enum["USD", "EUR"] }
                }
                workflow Main(text) {
                    extract Invoice from text -> invoice
                    return invoice.currency
                }
                """)
                                        .tokenize())
                        .parseScript();
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), new MockModels());
        executor.initialize();
        executor.executeWorkflow("Main", Map.of("text", "anything"));

        assertThat(executor.getContext().getAll().get("result")).isEqualTo("USD");
    }
}
