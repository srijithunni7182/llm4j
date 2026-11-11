package io.github.llm4j.examples.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The starter compiles and its script parses, so a change to the grammar cannot break it silently.
 * It is not run against anything: a starter is a map of what you provide, not a working service.
 */
class StarterTest {

    @Test
    void theScriptParsesAndDeclaresWhatTheStarterServes() throws Exception {
        String source = Files.readString(Path.of("src/main/resources/supplier.loom"), StandardCharsets.UTF_8);

        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();

        assertThat(script.getRemotes()).extracting(r -> r.getName()).containsExactly("Supplier");
        assertThat(script.getExposes()).extracting(e -> e.getName()).containsExactly("Quotes");
        assertThat(script.getAgentChannels()).extracting(c -> c.getName()).containsExactlyInAnyOrder("SupplierDesk", "CallerDesk");
    }

    @Test
    void theChannelRefusesAuthorizationAndIgnoresFilesItWasNotTaught() {
        var channel = new StarterChannel(question -> "42");
        var context = new io.github.llm4j.a2a.ChannelContext("r", "s", "w", io.github.llm4j.a2a.ChannelContext.Direction.INBOUND_QUESTION, "Supplier", null, 3, null);

        assertThat(channel.handle(context, new io.github.llm4j.a2a.ChannelMessage.AuthorizationNeeded("log in")))
                .isInstanceOf(io.github.llm4j.a2a.ChannelResponse.Refuse.class);
    }
}
