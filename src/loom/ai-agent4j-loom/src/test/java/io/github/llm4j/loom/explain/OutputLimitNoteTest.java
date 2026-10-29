package io.github.llm4j.loom.explain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import org.junit.jupiter.api.Test;

/** {@code weave explain} says what limit an answer is sent with, and where it comes from. */
class OutputLimitNoteTest {
    private static String explain(String source) {
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        return ScriptExplainer.explain(script, "x.loom", null);
    }

    private static final String BODY = "workflow Main() { delegate \"hi\" to A -> o }\n";

    @Test
    void anAgentWithoutPerCallUnderABudgetNamesTheModelsMaximum() {
        String text =
                explain(
                        "budget { tokens: 100000 }\nagent A { model: \"claude-opus-5-5\" }\n"
                                + BODY);

        assertThat(text).contains("A may write up to 128,000 tokens per answer (the model's maximum");
    }

    @Test
    void anUnknownModelNamesTheDefaultInstead() {
        String text = explain("budget { tokens: 100000 }\nagent A { model: \"my-model\" }\n" + BODY);

        assertThat(text).contains("A may write up to 8,192 tokens per answer (the default");
    }

    @Test
    void perCallIsNamedAsTheAgentsOwnLimit() {
        String text =
                explain(
                        "budget { tokens: 100000 }\nagent A { model: \"my-model\"\n budget { per_call: 2000 } }\n"
                                + BODY);

        assertThat(text).contains("A may write up to 2,000 tokens per answer (its per_call");
    }

    @Test
    void withNoBudgetThereIsNothingToSay() {
        assertThat(explain("agent A { model: \"my-model\" }\n" + BODY)).doesNotContain("per answer");
    }
}
