package io.github.llm4j.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.PromptCache;
import io.github.llm4j.model.ToolSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Prompt caching on the Messages API (docs/specs/31-multimodal-input, R1 and R6): a breakpoint is a
 * {@code cache_control} marker on the last block of the marked part, and nothing else changes.
 */
class PromptCacheBodyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODEL = "claude-sonnet-5-5";

    private static JsonNode body(LLMRequest request) throws Exception {
        AnthropicProvider provider =
                new AnthropicProvider(LLMConfig.builder().apiKey("test-key").build());
        return JSON.readTree(provider.body(request, MODEL, false));
    }

    private static java.util.Set<String> names(JsonNode node) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static LLMRequest.Builder conversation() {
        return LLMRequest.builder()
                .addSystemMessage("You are terse.")
                .addUserMessage("first question")
                .addAssistantMessage("first answer")
                .addUserMessage("second question");
    }

    private static ToolSpec tool(String name) {
        return new ToolSpec(name, "does " + name, Map.of("type", "object"));
    }

    @Test
    void aRequestWithoutACacheHasNoMarkerAndKeepsItsPlainSystemString() throws Exception {
        JsonNode body = body(conversation().build());

        assertThat(body.toString()).doesNotContain("cache_control");
        assertThat(body.get("system").isTextual()).isTrue();
    }

    @Test
    void theSystemPromptBecomesOneMarkedBlock() throws Exception {
        JsonNode body =
                body(conversation().promptCache(PromptCache.builder().system().build()).build());

        JsonNode system = body.get("system");
        assertThat(system.isArray()).isTrue();
        assertThat(system).hasSize(1);
        assertThat(system.get(0).get("type").asText()).isEqualTo("text");
        assertThat(system.get(0).get("text").asText()).isEqualTo("You are terse.");
        assertThat(system.at("/0/cache_control/type").asText()).isEqualTo("ephemeral");
    }

    @Test
    void onlyTheLastToolIsMarked() throws Exception {
        JsonNode body =
                body(
                        conversation()
                                .tools(List.of(tool("search"), tool("fetch")))
                                .promptCache(PromptCache.builder().tools().build())
                                .build());

        assertThat(body.at("/tools/0").has("cache_control")).isFalse();
        assertThat(body.at("/tools/1/cache_control/type").asText()).isEqualTo("ephemeral");
    }

    @Test
    void aMarkedMessageEndsInAMarkedBlockAndItsNeighboursStayPlain() throws Exception {
        // message 0 is the system turn; 2 is the assistant's first answer
        JsonNode body =
                body(
                        conversation()
                                .promptCache(PromptCache.builder().throughMessage(2).build())
                                .build());

        JsonNode messages = body.get("messages");
        assertThat(messages.get(0).get("content").isTextual()).isTrue();
        assertThat(messages.at("/1/content/0/text").asText()).isEqualTo("first answer");
        assertThat(messages.at("/1/content/0/cache_control/type").asText()).isEqualTo("ephemeral");
        assertThat(messages.get(2).get("content").isTextual()).isTrue();
    }

    @Test
    void theMarkerLandsOnTheLastBlockWhenTurnsOfOneRoleAreMerged() throws Exception {
        LLMRequest request =
                LLMRequest.builder()
                        .addUserMessage("part one")
                        .addUserMessage("part two")
                        .promptCache(PromptCache.builder().throughMessage(0).build())
                        .build();

        JsonNode content = body(request).at("/messages/0/content");

        // Marking message 0 caches everything up to the end of that message, not the merged turn
        assertThat(content.get(0).get("text").asText()).isEqualTo("part one");
        assertThat(content.get(0).has("cache_control")).isTrue();
        assertThat(content.get(1).has("cache_control")).isFalse();
    }

    @Test
    void markingASystemMessageMarksTheSystemBlock() throws Exception {
        JsonNode body =
                body(
                        conversation()
                                .promptCache(PromptCache.builder().throughMessage(0).build())
                                .build());

        assertThat(body.at("/system/0/cache_control/type").asText()).isEqualTo("ephemeral");
    }

    @Test
    void allFourBreakpointsAreWritten() throws Exception {
        JsonNode body =
                body(
                        conversation()
                                .tools(List.of(tool("search")))
                                .promptCache(
                                        PromptCache.builder()
                                                .system()
                                                .tools()
                                                .throughMessage(2)
                                                .throughMessage(3)
                                                .build())
                                .build());

        assertThat(body.toString().split("cache_control", -1)).hasSize(5);
    }

    @Test
    void anIndexBeyondTheMessagesIsRefusedBeforeTheCall() {
        LLMRequest request =
                conversation().promptCache(PromptCache.builder().throughMessage(9).build()).build();

        assertThatThrownBy(() -> body(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("9")
                .hasMessageContaining("4 messages");
    }

    @Test
    void cachingChangesOnlyTheMarkers() throws Exception {
        LLMRequest plain = conversation().tools(List.of(tool("search"))).build();
        LLMRequest cached =
                plain.toBuilder()
                        .promptCache(PromptCache.builder().system().tools().build())
                        .build();

        JsonNode withMarkers = body(cached);
        JsonNode without = body(plain);

        assertThat(names(withMarkers)).isEqualTo(names(without));
        assertThat(withMarkers.get("messages")).isEqualTo(without.get("messages"));
        assertThat(withMarkers.at("/system/0/text").asText())
                .isEqualTo(without.get("system").asText());
        assertThat(withMarkers.at("/tools/0/name")).isEqualTo(without.at("/tools/0/name"));
        assertThat(withMarkers.at("/tools/0/input_schema"))
                .isEqualTo(without.at("/tools/0/input_schema"));
    }

    @Test
    void aToolResultTurnCanBeMarkedToo() throws Exception {
        LLMRequest request =
                LLMRequest.builder()
                        .addUserMessage("look it up")
                        .addMessage(Message.toolResult("call-1", "search", "found it"))
                        .promptCache(PromptCache.builder().throughMessage(1).build())
                        .build();

        // a tool result is a user turn, so it is merged into the one before it
        JsonNode content = body(request).at("/messages/0/content");

        assertThat(content.get(content.size() - 1).get("type").asText()).isEqualTo("tool_result");
        assertThat(content.get(content.size() - 1).has("cache_control")).isTrue();
    }
}
