package io.github.llm4j.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCallDelta;
import io.github.llm4j.model.ToolSpec;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Streamed reasoning and tool calls: only on request for reasoning, never in the answer text. */
class StreamedReasoningTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private LLMConfig config(String model) {
        return LLMConfig.builder()
                .apiKey("k")
                .baseUrl(server.url("").toString().replaceAll("/+$", ""))
                .defaultModel(model)
                .build();
    }

    private static MockResponse sse(String body) {
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body);
    }

    private static String event(String name, String data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
    }

    private static LLMRequest ask(boolean reasoning) {
        return LLMRequest.builder()
                .addUserMessage("go")
                .reasoning(reasoning)
                .tools(List.of(new ToolSpec("lookup", "finds things", Map.of("type", "object"))))
                .build();
    }

    private static String answer(List<LLMResponse> chunks) {
        return chunks.stream().map(LLMResponse::getContent).collect(Collectors.joining());
    }

    private static List<String> reasoning(List<LLMResponse> chunks) {
        return chunks.stream().map(LLMResponse::getReasoning).filter(r -> r != null).toList();
    }

    private static List<ToolCallDelta> deltas(List<LLMResponse> chunks) {
        return chunks.stream().map(LLMResponse::getToolCallDelta).filter(d -> d != null).toList();
    }

    // ---- Anthropic ----

    private String anthropicStream() {
        return event(
                        "message_start",
                        "{\"type\":\"message_start\",\"message\":{\"model\":\"claude-opus-5-5\",\"usage\":{\"input_tokens\":5}}}")
                + event(
                        "content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}")
                + event(
                        "content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"I should \"}}")
                + event(
                        "content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"look it up.\"}}")
                + event(
                        "content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig\"}}")
                + event(
                        "content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                + event(
                        "content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Looking.\"}}")
                + event(
                        "content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"lookup\",\"input\":{}}}")
                + event(
                        "content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"q\\\":\"}}")
                + event(
                        "content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"cats\\\"}\"}}")
                + event(
                        "message_delta",
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":9}}")
                + event("message_stop", "{\"type\":\"message_stop\"}");
    }

    @Test
    void anthropicStreamsReasoningWhenAskedAndKeepsItOutOfTheAnswer() throws Exception {
        server.enqueue(sse(anthropicStream()));
        List<LLMResponse> chunks =
                new AnthropicProvider(config("claude-opus-5-5")).chatStream(ask(true)).toList();

        assertThat(reasoning(chunks)).containsExactly("I should ", "look it up.");
        assertThat(answer(chunks)).isEqualTo("Looking.");
        JsonNode sent = JSON.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(sent.path("thinking").path("type").asText()).isEqualTo("adaptive");
        assertThat(sent.path("thinking").path("display").asText()).isEqualTo("summarized");
    }

    @Test
    void anthropicSendsNoThinkingAndStreamsNoReasoningByDefault() throws Exception {
        server.enqueue(sse(anthropicStream()));
        List<LLMResponse> chunks =
                new AnthropicProvider(config("claude-opus-5-5")).chatStream(ask(false)).toList();

        assertThat(reasoning(chunks)).isEmpty();
        assertThat(answer(chunks)).isEqualTo("Looking.");
        assertThat(JSON.readTree(server.takeRequest().getBody().readUtf8()).has("thinking"))
                .isFalse();
    }

    @Test
    void anthropicStreamsAToolCallInPiecesThatJoinIntoItsArguments() {
        server.enqueue(sse(anthropicStream()));
        List<ToolCallDelta> deltas =
                deltas(
                        new AnthropicProvider(config("claude-opus-5-5"))
                                .chatStream(ask(false))
                                .toList());

        assertThat(deltas).hasSize(3);
        assertThat(deltas.get(0).id()).isEqualTo("toolu_1");
        assertThat(deltas.get(0).name()).isEqualTo("lookup");
        assertThat(deltas).allSatisfy(d -> assertThat(d.index()).isEqualTo(2));
        assertThat(
                        deltas.stream()
                                .map(ToolCallDelta::argumentsFragment)
                                .collect(Collectors.joining()))
                .isEqualTo("{\"q\":\"cats\"}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLastAnthropicChunkCarriesTheBlocksToSendBackWithTheToolResult() {
        server.enqueue(sse(anthropicStream()));
        List<LLMResponse> chunks =
                new AnthropicProvider(config("claude-opus-5-5")).chatStream(ask(false)).toList();

        Map<String, Object> data = chunks.get(chunks.size() - 1).getProviderData();
        List<Map<String, Object>> blocks =
                (List<Map<String, Object>>) data.get("anthropic.content");
        assertThat(blocks)
                .extracting(b -> b.get("type"))
                .containsExactly("thinking", "text", "tool_use");
        assertThat(blocks.get(0).get("thinking")).isEqualTo("I should look it up.");
        assertThat(blocks.get(0).get("signature")).isEqualTo("sig");
        assertThat(blocks.get(1).get("text")).isEqualTo("Looking.");
        assertThat(blocks.get(2).get("input")).isEqualTo(Map.of("q", "cats"));
    }

    @Test
    void anOlderAnthropicModelIsAskedForNoThinking() throws Exception {
        server.enqueue(sse(anthropicStream()));
        new AnthropicProvider(config("claude-haiku-4-5")).chatStream(ask(true)).toList();
        assertThat(JSON.readTree(server.takeRequest().getBody().readUtf8()).has("thinking"))
                .isFalse();
    }

    // ---- Gemini ----

    private static String data(String json) {
        return "data: " + json + "\r\n\r\n";
    }

    private String geminiStream() {
        return data(
                        "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Need a lookup.\",\"thought\":true}]}}]}")
                + data(
                        "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Looking.\"},{\"functionCall\":{\"name\":\"lookup\",\"args\":{\"q\":\"cats\"}}}]}}]}")
                + data(
                        "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":3}}");
    }

    @Test
    void geminiStreamsThoughtsWhenAskedAndAsksForThem() throws Exception {
        server.enqueue(sse(geminiStream()));
        List<LLMResponse> chunks =
                new GoogleProvider(config("gemini-2.5-flash")).chatStream(ask(true)).toList();

        assertThat(reasoning(chunks)).containsExactly("Need a lookup.");
        assertThat(answer(chunks)).isEqualTo("Looking.");
        JsonNode sent = JSON.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(
                        sent.path("generationConfig")
                                .path("thinkingConfig")
                                .path("includeThoughts")
                                .asBoolean())
                .isTrue();
    }

    @Test
    void geminiStreamsNoThoughtsByDefault() throws Exception {
        server.enqueue(sse(geminiStream()));
        List<LLMResponse> chunks =
                new GoogleProvider(config("gemini-2.5-flash")).chatStream(ask(false)).toList();

        assertThat(reasoning(chunks)).isEmpty();
        assertThat(answer(chunks)).isEqualTo("Looking.");
        assertThat(
                        JSON.readTree(server.takeRequest().getBody().readUtf8())
                                .path("generationConfig")
                                .has("thinkingConfig"))
                .isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLastGeminiChunkCarriesItsPartsWhenACallWasMade() {
        server.enqueue(sse(geminiStream()));
        List<LLMResponse> chunks =
                new GoogleProvider(config("gemini-2.5-flash")).chatStream(ask(false)).toList();

        List<Map<String, Object>> parts =
                (List<Map<String, Object>>)
                        chunks.get(chunks.size() - 1).getProviderData().get("google.parts");
        assertThat(parts).anyMatch(p -> p.containsKey("functionCall"));
    }

    @Test
    void geminiSendsAFunctionCallWholeAsOneDelta() {
        server.enqueue(sse(geminiStream()));
        List<ToolCallDelta> deltas =
                deltas(
                        new GoogleProvider(config("gemini-2.5-flash"))
                                .chatStream(ask(false))
                                .toList());

        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0).index()).isEqualTo(0);
        assertThat(deltas.get(0).name()).isEqualTo("lookup");
        assertThat(deltas.get(0).argumentsFragment()).isEqualTo("{\"q\":\"cats\"}");
    }
}
