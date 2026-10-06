package io.github.llm4j.agent.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PromptFrontMatterTest {

    @Test
    void descriptionAndAFlowListOfVariables() {
        var p = PromptFrontMatter.parse("description: Finds background\nvariables: [topic, region]");

        assertThat(p.description()).isEqualTo("Finds background");
        assertThat(p.variables()).containsExactly("topic", "region");
    }

    @Test
    void aBlockListQuotesCommentsAndOtherKeys() {
        var p = PromptFrontMatter.parse("# a note\nowner: someone\ndescription: \"Quoted: with a colon\"\nvariables:\n  - topic\n  - 'region'\n\n");

        assertThat(p.description()).isEqualTo("Quoted: with a colon");
        assertThat(p.variables()).containsExactly("topic", "region");
    }

    @Test
    void emptyThingsAreNone() {
        var p = PromptFrontMatter.parse("description:\nvariables: []");

        assertThat(p.description()).isNull();
        assertThat(p.variables()).isEmpty();
    }

    @Test
    void whatItCannotReadIsRefusedWithWhatToFix() {
        assertThatThrownBy(() -> PromptFrontMatter.parse("variables: [unclosed")).hasMessageContaining("not closed with ]");
        assertThatThrownBy(() -> PromptFrontMatter.parse("variables: topic")).hasMessageContaining("must be a list");
        assertThatThrownBy(() -> PromptFrontMatter.parse("description: |\n  two lines")).hasMessageContaining("one line");
        assertThatThrownBy(() -> PromptFrontMatter.parse("just words")).hasMessageContaining("is not a key: value pair");
        assertThatThrownBy(() -> PromptFrontMatter.parse("  indented: x")).hasMessageContaining("is not a key: value pair");
    }
}
