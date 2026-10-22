package io.github.llm4j.loom.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import org.junit.jupiter.api.Test;

/** R1, R2: the context block on an agent. */
class ContextBlockParserTest {

    private static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    private static AgentDef.ContextConfig context(String body) {
        return parse(
                        "agent A {\n model: \"gemini-2.5-flash\"\n"
                                + body
                                + "\n}\nworkflow Main() { delegate \"x\" to A -> out }\n")
                .getAgents()
                .get(0)
                .getContext();
    }

    @Test
    void everyKeyIsRead() {
        AgentDef.ContextConfig c =
                context(
                        """
                context {
                    compact: chain(trim_tool_results, summarize, drop_oldest)
                    summarizer: "claude-haiku-5-5"
                    trigger: 80%
                    target: 55%
                    keep_recent: 6
                    max_compactions: 20
                }""");
        assertThat(c.compactNames())
                .containsExactly("trim_tool_results", "summarize", "drop_oldest");
        assertThat(c.getSummarizer()).isEqualTo("claude-haiku-5-5");
        assertThat(c.getTrigger()).isEqualTo(0.80);
        assertThat(c.getTarget()).isEqualTo(0.55);
        assertThat(c.getInt("keep_recent", -1)).isEqualTo(6);
        assertThat(c.getInt("max_compactions", -1)).isEqualTo(20);
        assertThat(c.getLine()).isEqualTo(3);
        assertThat(c.lineOf("trigger")).isEqualTo(6);
    }

    @Test
    void aSingleStrategyIsEnough() {
        AgentDef.ContextConfig c = context("context { compact: drop_oldest }");
        assertThat(c.compactNames()).containsExactly("drop_oldest");
        assertThat(c.getTrigger()).isNull();
    }

    @Test
    void aColonAfterTheWordIsAllowedAsForOtherBlocks() {
        assertThat(context("context: { compact: none }").compactNames()).containsExactly("none");
    }

    @Test
    void aListInBracketsIsAChain() {
        assertThat(context("context { compact: [trim_tool_results, drop_oldest] }").compactNames())
                .containsExactly("trim_tool_results", "drop_oldest");
    }

    @Test
    void percentageSpellings() {
        assertThat(context("context { compact: none  trigger: 90%  target: 40 % }").getTarget())
                .isEqualTo(0.40);
        assertThat(context("context { compact: none  trigger: 90% }").getTrigger()).isEqualTo(0.90);
    }

    @Test
    void aFractionIsKeptAsWrittenSoTheCheckCanExplainIt() {
        assertThat(context("context { compact: none  trigger: 0.8 }").getTrigger()).isNull();
        assertThat(context("context { compact: none  trigger: 0.8 }").has("trigger")).isTrue();
    }

    @Test
    void anAgentWithoutTheBlockHasNone() {
        assertThat(
                        parse(
                                        "agent A { model: \"gemini-2.5-flash\" }\nworkflow Main() { delegate \"x\" to A -> out }")
                                .getAgents()
                                .get(0)
                                .getContext())
                .isNull();
    }

    @Test
    void emptyChainIsASyntaxError() {
        assertThatThrownBy(() -> context("context { compact: chain() }"))
                .hasMessageContaining("strategy");
    }

    @Test
    void aKeyGivenTwiceIsASyntaxError() {
        assertThatThrownBy(() -> context("context { compact: none  compact: none }"))
                .hasMessageContaining("twice");
    }

    @Test
    void theWordContextElsewhereStillParses() {
        LoomScript s =
                parse(
                        """
                agent A { model: "gemini-2.5-flash"  system: "Use the context you are given." }
                workflow Main() { delegate "context" to A -> context }
                """);
        assertThat(s.getAgents().get(0).getContext()).isNull();
    }

    @Test
    void optionsOfAStrategyAreExtraKeys() {
        AgentDef.ContextConfig c =
                context("context { compact: summarize  max_summary_tokens: 800 }");
        assertThat(c.extraOptions())
                .containsEntry("max_summary_tokens", "800")
                .doesNotContainKey("compact");
    }
}
