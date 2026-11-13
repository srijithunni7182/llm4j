package io.github.llm4j.loom.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskEffect;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A tool an agent names without declaring it: a built-in name is what its kind says; any other name
 * is the host's own tool, judged by what it declares and never by its name (phase 35, C2).
 */
class ToolNamesTest {

    @Test
    void aBuiltInNameIsWhatItsKindSays() {
        Capabilities search = Capabilities.resolve("web_search", Map.of(), Map.of());
        assertThat(search.known()).isTrue();
        assertThat(search.untrusted()).isTrue();
        assertThat(search.note()).isEqualTo("web search results");

        Capabilities calculator = Capabilities.resolve("calculator", Map.of(), Map.of());
        assertThat(calculator.level()).isEqualTo(TaskEffect.NONE);
        assertThat(calculator.note()).isEqualTo("built-in");
    }

    @Test
    void aHostToolNamedLikeAKindIsNotTrustedForItsName() {
        for (String name : new String[] {"duckduckgo", "serpapi", "google_search", "webhook"}) {
            Capabilities c = Capabilities.resolve(name, Map.of(), Map.of());
            assertThat(c.known()).as(name).isFalse();
            assertThat(c.outward()).as(name).isTrue();
        }
    }

    @Test
    void aHostToolThatDeclaresItsReachIsBelieved() {
        Capabilities c =
                Capabilities.resolve("duckduckgo", Map.of(), Map.of("duckduckgo", "fetches"));
        assertThat(c.known()).isTrue();
        assertThat(c.untrusted()).isTrue();
        assertThat(c.outward()).isFalse();
    }
}
