package io.github.llm4j.loom.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.a2a.A2AJsonRpc10;
import io.github.llm4j.a2a.AgentCardBuilder;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.host.ExposedCard;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import org.junit.jupiter.api.Test;

/**
 * Phase 32d, R25: the card an {@code expose} block shows to outsiders, built from the script alone.
 * A reviewer reads it in a diff, so it must say what is accepted and nothing about what is inside.
 */
class ExposedCardTest {

    private static LoomScript script(String text) {
        return new LoomParser(new Lexer(text).tokenize()).parseScript();
    }

    private static final String TEXT_IN = """
            agent_channel Desk { audience: caller }
            expose Totals {
                workflow: Total
                description: "Totals a receipt."
                agent_channel: Desk
                text_input: receipt
            }
            workflow Total(receipt) { return "ok" }
            """;

    @Test
    void theCardNamesTheExposeBlockAndSaysWhatItAccepts() {
        LoomScript s = script(TEXT_IN);

        AgentCardBuilder.Published card = ExposedCard.of(s.getExposes().get(0), s, "https://agent.example.com/a2a");

        assertThat(card.view().name()).isEqualTo("Totals");
        assertThat(card.view().description()).isEqualTo("Totals a receipt.");
        assertThat(card.view().defaultInputMediaTypes()).containsExactly("text/plain");
        assertThat(card.view().endpoints()).singleElement().satisfies(e -> assertThat(e.url()).isEqualTo("https://agent.example.com/a2a"));
        assertThat(card.view().skills()).singleElement().satisfies(k -> assertThat(k.id()).isEqualTo("Total"));
    }

    @Test
    void aWorkflowWithMoreParametersAlsoAcceptsJsonAndOneWithNoTextInputOnlyJson() {
        LoomScript s = script(TEXT_IN.replace("text_input: receipt", "").replace("Total(receipt)", "Total(receipt, currency)"));

        var card = ExposedCard.of(s.getExposes().get(0), s, "https://agent.example.com/a2a");

        assertThat(card.view().defaultInputMediaTypes()).containsExactly("application/json");
    }

    @Test
    void declaredSkillsReplaceTheDerivedOne() {
        LoomScript s = script(TEXT_IN.replace("text_input: receipt", "text_input: receipt skills: [ { id: \"total\" name: \"Total a receipt\" description: \"Adds the lines.\" } ]"));

        var card = ExposedCard.of(s.getExposes().get(0), s, "https://agent.example.com/a2a");

        assertThat(card.view().skills()).singleElement().satisfies(k -> {
            assertThat(k.id()).isEqualTo("total");
            assertThat(k.name()).isEqualTo("Total a receipt");
        });
    }

    @Test
    void theJsonIsTheOneAClientReadsAndNamesNothingOfTheScript() {
        LoomScript s = script(TEXT_IN);

        var card = ExposedCard.of(s.getExposes().get(0), s, "https://agent.example.com/a2a");

        assertThat(A2AJsonRpc10.parseCard(card.json()).name()).isEqualTo("Totals");
        assertThat(card.json()).doesNotContain("Desk").doesNotContain("agent_channel").doesNotContain("return");
    }

    @Test
    void theAddressMustBeHttpsOrACardCouldPointAClientAtAnUnsafePlace() {
        LoomScript s = script(TEXT_IN);

        assertThatThrownBy(() -> ExposedCard.of(s.getExposes().get(0), s, "http://agent.example.com/a2a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }
}
