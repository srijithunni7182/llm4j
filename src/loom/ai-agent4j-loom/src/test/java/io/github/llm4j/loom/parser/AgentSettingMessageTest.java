package io.github.llm4j.loom.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.lexer.Lexer;
import org.junit.jupiter.api.Test;

/** A word an agent cannot have is named, with what it can have and the nearest guess, instead of "IDENTIFIER". */
class AgentSettingMessageTest {

    static void parse(String source) {
        new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    @Test
    void anUnknownSettingIsNamedWithTheListAndAGuess() {
        assertThatThrownBy(() -> parse("agent A {\n    model: \"m\"\n    temprature: 0.2\n}\n"))
                .hasMessageContaining("line 3")
                .hasMessageContaining("\"temprature\" is not something an agent can have")
                .hasMessageContaining("An agent can have: model, system, system_template, prompt, persona, tools")
                .hasMessageContaining("Did you mean temperature?")
                .hasMessageNotContaining("agent body: IDENTIFIER");
    }

    @Test
    void aWordWithNoCloseGuessStillListsWhatIsAllowed() {
        assertThatThrownBy(() -> parse("agent A {\n    description: \"x\"\n}\n"))
                .hasMessageContaining("\"description\" is not something an agent can have")
                .hasMessageContaining("An agent can have:")
                .hasMessageNotContaining("Did you mean");
    }

    @Test
    void somethingThatIsNotAWordAtAllIsNamedToo() {
        assertThatThrownBy(() -> parse("agent A {\n    \"stray\"\n}\n"))
                .hasMessageContaining("Unexpected \"stray\" inside an agent; an agent can have:");
    }
}
