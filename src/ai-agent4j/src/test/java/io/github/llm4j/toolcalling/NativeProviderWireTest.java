package io.github.llm4j.toolcalling;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolSchema;
import io.github.llm4j.model.ToolSpec;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.ollama.OllamaProvider;
import io.github.llm4j.provider.sarvam.SarvamChatProvider;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TCP-*: what Gemini and Claude are sent and how their tool calls are read, against a local mock server (no network, no keys). */
class NativeProviderWireTest {

    static final ObjectMapper JSON = new ObjectMapper();

    MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    LLMConfig config() {
        return LLMConfig.builder().apiKey("test-key-0001").baseUrl(server.url("/").toString().replaceAll("/$", "")).defaultModel("m")
                .retryPolicy(RetryPolicy.builder().maxRetries(0).build()).build();
    }

    static final ToolSpec CALC = new ToolSpec("calc", "adds numbers",
            ToolSchema.object().string("expression", "e.g. 2+2", true).build());
    static final ToolSpec FREE = new ToolSpec("legacy", "free form tool", null);

    JsonNode sent() throws Exception {
        RecordedRequest r = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(r, "a request was made");
        return JSON.readTree(r.getBody().readUtf8());
    }

    void enqueue(String body) {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));
    }

    // ── Google ──────────────────────────────────────────────────────────

    static final String GEMINI_TEXT = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"4\"}]},\"finishReason\":\"STOP\"}],"
            + "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":1,\"totalTokenCount\":6}}";
    static final String GEMINI_CALL = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
            + "{\"text\":\"Let me add\"},"
            + "{\"functionCall\":{\"name\":\"calc\",\"args\":{\"expression\":\"2+2\"}},\"thoughtSignature\":\"SIG-ABC\"},"
            + "{\"functionCall\":{\"name\":\"legacy\",\"args\":{\"input\":\"x\"}}}"
            + "]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":3,\"totalTokenCount\":8}}";

    @Test
    void tcp01_googleSendsFunctionDeclarationsAndToolConfig() throws Exception {
        enqueue(GEMINI_TEXT);
        new GoogleProvider(config()).chat(LLMRequest.builder().addUserMessage("2+2?").tools(List.of(CALC, FREE)).build());
        JsonNode body = sent();
        JsonNode decls = body.path("tools").path(0).path("functionDeclarations");
        assertEquals(2, decls.size());
        assertEquals("calc", decls.path(0).path("name").asText());
        assertEquals("adds numbers", decls.path(0).path("description").asText());
        assertEquals("string", decls.path(0).path("parameters").path("properties").path("expression").path("type").asText());
        assertEquals("expression", decls.path(0).path("parameters").path("required").path(0).asText());
        assertEquals("AUTO", body.path("toolConfig").path("functionCallingConfig").path("mode").asText());
    }

    @Test
    void tcp01_googleDropsKeywordsItRefusesAndOffersAFreeFormInputToUndeclaredTools() throws Exception {
        enqueue(GEMINI_TEXT);
        ToolSpec strict = new ToolSpec("strict", "d", Map.of("type", "object", "additionalProperties", false, "$schema", "x",
                "properties", Map.of("a", Map.of("type", "string", "default", "z", "examples", List.of("q")))));
        new GoogleProvider(config()).chat(LLMRequest.builder().addUserMessage("hi").tools(List.of(strict, FREE)).build());
        JsonNode decls = sent().path("tools").path(0).path("functionDeclarations");
        String strictJson = decls.path(0).path("parameters").toString();
        for (String refused : new String[] {"additionalProperties", "$schema", "default", "examples"}) assertFalse(strictJson.contains(refused), strictJson);
        assertTrue(strictJson.contains("\"a\""));
        JsonNode free = decls.path(1).path("parameters");
        assertEquals("object", free.path("type").asText());
        assertEquals("string", free.path("properties").path("input").path("type").asText(), "an object with no properties is refused by Gemini");
    }

    @Test
    void tcp01_googleToolChoiceNoneAndNoToolsMeansNoToolFields() throws Exception {
        enqueue(GEMINI_TEXT);
        new GoogleProvider(config()).chat(LLMRequest.builder().addUserMessage("hi").tools(List.of(CALC)).toolChoice(LLMRequest.ToolChoice.NONE).build());
        assertEquals("NONE", sent().path("toolConfig").path("functionCallingConfig").path("mode").asText());
        enqueue(GEMINI_TEXT);
        new GoogleProvider(config()).chat(LLMRequest.builder().addUserMessage("hi").build());
        JsonNode body = sent();
        assertTrue(body.path("tools").isMissingNode(), "a request without tools is unchanged");
        assertTrue(body.path("toolConfig").isMissingNode());
    }

    @Test
    void tcp01_googleReadsFunctionCallsAndKeepsTheRawParts() throws Exception {
        enqueue(GEMINI_CALL);
        LLMResponse r = new GoogleProvider(config()).chat(LLMRequest.builder().addUserMessage("2+2?").tools(List.of(CALC, FREE)).build());
        assertTrue(r.hasToolCalls());
        assertEquals(2, r.getToolCalls().size());
        assertEquals("calc", r.getToolCalls().get(0).name());
        assertEquals(Map.of("expression", "2+2"), r.getToolCalls().get(0).arguments());
        assertNull(r.getToolCalls().get(0).id(), "Gemini sends no ids");
        assertEquals("Let me add", r.getContent());
        assertEquals(LLMResponse.FinishReason.TOOL_CALLS, r.getFinishReason(), "STOP with a functionCall is a tool call");
        assertTrue(r.getProviderData().toString().contains("SIG-ABC"), "the thought signature is kept for the next turn");
        assertEquals(8, r.getTokenUsage().getTotalTokens());

        enqueue(GEMINI_TEXT);
        LLMResponse plain = new GoogleProvider(config()).chat(LLMRequest.builder().addUserMessage("hi").build());
        assertFalse(plain.hasToolCalls());
        assertEquals(LLMResponse.FinishReason.STOP, plain.getFinishReason());
        assertTrue(plain.getProviderData().isEmpty());
    }

    @Test
    void tcp03_googleSendsBackTheRawPartsAndMergesToolResults() throws Exception {
        enqueue(GEMINI_CALL);
        GoogleProvider google = new GoogleProvider(config());
        LLMResponse first = google.chat(LLMRequest.builder().addUserMessage("2+2?").tools(List.of(CALC, FREE)).build());
        sent();

        enqueue(GEMINI_TEXT);
        google.chat(LLMRequest.builder()
                .addSystemMessage("be brief")
                .addUserMessage("2+2?")
                .addMessage(Message.assistantToolCalls(first.getContent(), first.getToolCalls(), first.getProviderData()))
                .addMessage(Message.toolResult(null, "calc", "4"))
                .addMessage(Message.toolResult(null, "legacy", "done"))
                .tools(List.of(CALC, FREE)).build());
        JsonNode body = sent();
        JsonNode contents = body.path("contents");
        assertEquals(3, contents.size(), "user, model, then ONE user turn holding both results");
        assertEquals("user", contents.path(0).path("role").asText());
        assertEquals("model", contents.path(1).path("role").asText());
        JsonNode modelParts = contents.path(1).path("parts");
        assertEquals("SIG-ABC", modelParts.path(1).path("thoughtSignature").asText(), "the model's own parts, untouched");
        assertEquals(3, modelParts.size());
        JsonNode results = contents.path(2);
        assertEquals("user", results.path("role").asText());
        assertEquals(2, results.path("parts").size());
        assertEquals("calc", results.path("parts").path(0).path("functionResponse").path("name").asText());
        assertEquals("4", results.path("parts").path(0).path("functionResponse").path("response").path("result").asText());
        assertEquals("legacy", results.path("parts").path(1).path("functionResponse").path("name").asText());
        assertEquals("be brief", body.path("systemInstruction").path("parts").path(0).path("text").asText());
    }

    @Test
    void tcp03_googleBuildsFunctionCallPartsWhenThereIsNoRawData() throws Exception {
        enqueue(GEMINI_TEXT);
        new GoogleProvider(config()).chat(LLMRequest.builder()
                .addUserMessage("hi")
                .addMessage(Message.assistantToolCalls("thinking", List.of(new ToolCall("c1", "calc", Map.of("expression", "1+1"))), null))
                .addMessage(Message.toolResult("c1", "calc", "2"))
                .tools(List.of(CALC)).build());
        JsonNode parts = sent().path("contents").path(1).path("parts");
        assertEquals("thinking", parts.path(0).path("text").asText());
        assertEquals("calc", parts.path(1).path("functionCall").path("name").asText());
        assertEquals("1+1", parts.path(1).path("functionCall").path("args").path("expression").asText());
    }

    // ── Anthropic ───────────────────────────────────────────────────────

    static final String CLAUDE_TEXT = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-4-5\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"4\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}";
    static final String CLAUDE_CALL = "{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-4-5\",\"content\":["
            + "{\"type\":\"thinking\",\"thinking\":\"hmm\",\"signature\":\"SIG-XYZ\"},"
            + "{\"type\":\"text\",\"text\":\"Let me check\"},"
            + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"calc\",\"input\":{\"expression\":\"2+2\"}},"
            + "{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"legacy\",\"input\":{}}"
            + "],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":10,\"output_tokens\":7}}";

    @Test
    void tcp02_anthropicSendsToolsAndChoice() throws Exception {
        enqueue(CLAUDE_TEXT);
        new AnthropicProvider(config()).chat(LLMRequest.builder().addUserMessage("2+2?").tools(List.of(CALC, FREE)).build());
        JsonNode body = sent();
        assertEquals(2, body.path("tools").size());
        assertEquals("calc", body.path("tools").path(0).path("name").asText());
        assertEquals("adds numbers", body.path("tools").path(0).path("description").asText());
        assertEquals("string", body.path("tools").path(0).path("input_schema").path("properties").path("expression").path("type").asText());
        assertEquals("object", body.path("tools").path(1).path("input_schema").path("type").asText(), "undeclared tools accept any object");
        assertEquals("auto", body.path("tool_choice").path("type").asText());
        assertEquals("2+2?", body.path("messages").path(0).path("content").asText(), "plain turns keep their string form");

        enqueue(CLAUDE_TEXT);
        new AnthropicProvider(config()).chat(LLMRequest.builder().addUserMessage("hi").build());
        JsonNode plain = sent();
        assertTrue(plain.path("tools").isMissingNode());
        assertTrue(plain.path("tool_choice").isMissingNode());
    }

    @Test
    void tcp02_anthropicReadsToolUseBlocksAndKeepsTheRawContent() throws Exception {
        enqueue(CLAUDE_CALL);
        LLMResponse r = new AnthropicProvider(config()).chat(LLMRequest.builder().addUserMessage("2+2?").tools(List.of(CALC, FREE)).build());
        assertEquals(2, r.getToolCalls().size());
        assertEquals("toolu_1", r.getToolCalls().get(0).id());
        assertEquals("calc", r.getToolCalls().get(0).name());
        assertEquals(Map.of("expression", "2+2"), r.getToolCalls().get(0).arguments());
        assertEquals(Map.of(), r.getToolCalls().get(1).arguments());
        assertEquals("Let me check", r.getContent(), "the thinking block is not the answer");
        assertEquals(LLMResponse.FinishReason.TOOL_CALLS, r.getFinishReason());
        assertTrue(r.getProviderData().toString().contains("SIG-XYZ"));
        assertEquals(17, r.getTokenUsage().getTotalTokens());

        enqueue(CLAUDE_TEXT);
        LLMResponse plain = new AnthropicProvider(config()).chat(LLMRequest.builder().addUserMessage("hi").build());
        assertFalse(plain.hasToolCalls());
        assertEquals("4", plain.getContent());
        assertTrue(plain.getProviderData().isEmpty());
    }

    @Test
    void tcp03_anthropicSendsBackTheRawContentAndGroupsToolResults() throws Exception {
        enqueue(CLAUDE_CALL);
        AnthropicProvider claude = new AnthropicProvider(config());
        LLMResponse first = claude.chat(LLMRequest.builder().addUserMessage("2+2?").tools(List.of(CALC, FREE)).build());
        sent();

        enqueue(CLAUDE_TEXT);
        claude.chat(LLMRequest.builder()
                .addSystemMessage("be brief")
                .addUserMessage("2+2?")
                .addMessage(Message.assistantToolCalls(first.getContent(), first.getToolCalls(), first.getProviderData()))
                .addMessage(Message.toolResult("toolu_1", "calc", "4"))
                .addMessage(Message.toolResult("toolu_2", "legacy", "done"))
                .tools(List.of(CALC, FREE)).build());
        JsonNode body = sent();
        JsonNode messages = body.path("messages");
        assertEquals(3, messages.size(), "user, assistant, then ONE user message holding both results");
        JsonNode assistant = messages.path(1).path("content");
        assertEquals("thinking", assistant.path(0).path("type").asText(), "the thinking block comes back first, unchanged");
        assertEquals("SIG-XYZ", assistant.path(0).path("signature").asText());
        assertEquals("tool_use", assistant.path(2).path("type").asText());
        JsonNode results = messages.path(2).path("content");
        assertEquals("user", messages.path(2).path("role").asText());
        assertEquals(2, results.size());
        assertEquals("tool_result", results.path(0).path("type").asText());
        assertEquals("toolu_1", results.path(0).path("tool_use_id").asText());
        assertEquals("4", results.path(0).path("content").asText());
        assertEquals("toolu_2", results.path(1).path("tool_use_id").asText());
        assertEquals("be brief", body.path("system").asText());
    }

    @Test
    void tcp03_anthropicBuildsToolUseBlocksWhenThereIsNoRawData() throws Exception {
        enqueue(CLAUDE_TEXT);
        new AnthropicProvider(config()).chat(LLMRequest.builder()
                .addUserMessage("hi")
                .addMessage(Message.assistantToolCalls("thinking", List.of(new ToolCall("c1", "calc", Map.of("expression", "1+1"))), null))
                .addMessage(Message.toolResult("c1", "calc", "2"))
                .addUserMessage("and then?")
                .tools(List.of(CALC)).build());
        JsonNode messages = sent().path("messages");
        JsonNode assistant = messages.path(1).path("content");
        assertEquals("text", assistant.path(0).path("type").asText());
        assertEquals("tool_use", assistant.path(1).path("type").asText());
        assertEquals("c1", assistant.path(1).path("id").asText());
        assertEquals("1+1", assistant.path(1).path("input").path("expression").asText());
        JsonNode user = messages.path(2).path("content");
        assertEquals("tool_result", user.path(0).path("type").asText(), "the tool result leads the user message");
        assertEquals("text", user.path(1).path("type").asText());
        assertEquals("and then?", user.path(1).path("text").asText());
    }

    // ── capability ──────────────────────────────────────────────────────

    @Test
    void tcp05_onlyGoogleAndAnthropicClaimNativeToolCalling() {
        assertTrue(new GoogleProvider(config()).supportsToolCalling());
        assertTrue(new AnthropicProvider(config()).supportsToolCalling());
        LLMProvider sarvam = new SarvamChatProvider(config());
        LLMProvider ollama = new OllamaProvider(config());
        assertFalse(sarvam.supportsToolCalling());
        assertFalse(ollama.supportsToolCalling());
        assertTrue(new DefaultLLMClient(new GoogleProvider(config())).supportsToolCalling());
    }

    @Test
    void tcp05_aProviderWithoutToolCallingRefusesToolsBeforeSendingAnything() {
        DefaultLLMClient client = new DefaultLLMClient(new SarvamChatProvider(config()));
        assertThrows(InvalidRequestException.class, () -> client.chat(LLMRequest.builder().addUserMessage("hi").tools(List.of(CALC)).build()));
        assertEquals(0, server.getRequestCount());
    }
}
