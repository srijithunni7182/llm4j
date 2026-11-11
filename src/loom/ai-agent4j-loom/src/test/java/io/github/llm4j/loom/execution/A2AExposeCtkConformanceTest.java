package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.a2a.A2AJsonRpc10;
import io.github.llm4j.a2a.AgentCardBuilder;
import io.github.llm4j.a2a.AgentCardView;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.host.ExposedCard;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Runs the exposed-workflow scenario of the Loom Conformance Test Kit ({@code loom/ctk}): the card
 * an {@code expose} block shows to outsiders, built from the script alone. The expected card is in
 * terms of what a client reads (name, description, accepted media types, skills, address), so
 * another runtime can build the same card from the same file.
 */
class A2AExposeCtkConformanceTest {

    private static final Path CTK = Path.of("..", "ctk").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theCardMatchesTheCanonicalOne() throws Exception {
        JsonNode expected = JSON.readTree(Files.readString(CTK.resolve("traces/a2a_expose.json")));
        LoomScript script = new LoomParser(new Lexer(Files.readString(CTK.resolve("scripts/a2a_expose.loom"))).tokenize()).parseScript();

        AgentCardBuilder.Published card = ExposedCardAccess.build(script, expected.get("address").asText());

        JsonNode want = expected.get("card");
        AgentCardView read = A2AJsonRpc10.parseCard(card.json());
        assertEquals(want.get("name").asText(), read.name());
        assertEquals(want.get("description").asText(), read.description());
        List<String> accepted = new ArrayList<>();
        want.get("acceptedMediaTypes").forEach(t -> accepted.add(t.asText()));
        assertEquals(accepted, read.defaultInputMediaTypes());
        List<String> skills = new ArrayList<>();
        want.get("skillIds").forEach(t -> skills.add(t.asText()));
        assertEquals(skills, read.skills().stream().map(AgentCardView.Skill::id).toList());
        assertEquals(want.get("endpoint").asText(), read.endpoints().get(0).url());
        want.get("mustNotContain").forEach(t -> assertFalse(card.json().contains(t.asText()), "the card must not contain " + t.asText()));
    }

    @Test
    void anAddressThatIsNotHttpsIsRefused() throws Exception {
        JsonNode expected = JSON.readTree(Files.readString(CTK.resolve("traces/a2a_expose.json")));
        LoomScript script = new LoomParser(new Lexer(Files.readString(CTK.resolve("scripts/a2a_expose.loom"))).tokenize()).parseScript();

        assertThrows(IllegalArgumentException.class, () -> ExposedCardAccess.build(script, expected.get("refused").get("addressNotHttps").asText()));
    }

    /** The public entry point, named for what the scenario does. */
    private static final class ExposedCardAccess {
        static AgentCardBuilder.Published build(LoomScript script, String address) {
            return ExposedCard.of(script.getExposes().get(0), script, address);
        }
    }
}
