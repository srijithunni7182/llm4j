package io.github.llm4j.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.ResponseFormat;
import io.github.llm4j.model.StructuredOutput;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The response format on the Responses schema (docs/specs/30-native-json-output, R4): OpenAI and
 * xAI take it flat under {@code text.format}; Perplexity's Agent API takes {@code response_format}
 * with a nested {@code json_schema}. Only for the models recorded as accepting it.
 */
class StructuredOutputResponsesWireTest {

    private static final String REPLY =
            "{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                    + "\"content\":[{\"type\":\"output_text\",\"text\":\"{}\"}]}],"
                    + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ResponseFormat FORMAT =
            ResponseFormat.jsonSchema(
                    "invoice",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("vendor", Map.of("type", "string")),
                            "required",
                            List.of("vendor"),
                            "additionalProperties",
                            false));

    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        server.enqueue(
                new MockResponse().setHeader("Content-Type", "application/json").setBody(REPLY));
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private LLMConfig config() {
        return LLMConfig.builder()
                .apiKey("test-key")
                .baseUrl(server.url("/v1").toString().replaceAll("/$", ""))
                .build();
    }

    private JsonNode send(LLMClient client, String model) throws Exception {
        client.chat(
                LLMRequest.builder()
                        .addUserMessage("hi")
                        .model(model)
                        .responseFormat(FORMAT)
                        .build());
        return JSON.readTree(server.takeRequest().getBody().readUtf8());
    }

    @Test
    void openAiTakesItFlatUnderTextFormat() throws Exception {
        LLMClient client = new DefaultLLMClient(OpenAiResponsesProvider.openai(config()));

        JsonNode body = send(client, "gpt-4o");

        JsonNode format = body.at("/text/format");
        assertThat(format.get("type").asText()).isEqualTo("json_schema");
        assertThat(format.get("name").asText()).isEqualTo("invoice");
        assertThat(format.get("strict").asBoolean()).isTrue();
        assertThat(format.at("/schema/additionalProperties").asBoolean()).isFalse();
        assertThat(body.has("response_format")).isFalse();
    }

    @Test
    void xaiTakesItTheSameWayOnAGrok4Model() throws Exception {
        LLMClient client = new DefaultLLMClient(OpenAiResponsesProvider.xai(config()));

        JsonNode body = send(client, "grok-4");

        assertThat(body.at("/text/format/type").asText()).isEqualTo("json_schema");
        assertThat(body.at("/text/format/name").asText()).isEqualTo("invoice");
    }

    @Test
    void perplexityTakesItAsResponseFormatWithANestedSchema() throws Exception {
        LLMClient client = new DefaultLLMClient(OpenAiResponsesProvider.perplexity(config()));

        JsonNode body = send(client, "openai/gpt-5-mini");

        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(body.at("/response_format/json_schema/name").asText()).isEqualTo("invoice");
        assertThat(body.at("/response_format/json_schema/schema/required/0").asText())
                .isEqualTo("vendor");
        assertThat(body.has("text")).isFalse();
    }

    @Test
    void aModelNobodyRecordedGetsNoFormat() throws Exception {
        LLMClient client = new DefaultLLMClient(OpenAiResponsesProvider.openai(config()));

        JsonNode body = send(client, "gpt-3.5-turbo");

        assertThat(body.has("text")).isFalse();
    }

    @Test
    void theCapabilityMatchesWhatTheWriterDoes() {
        assertThat(
                        new DefaultLLMClient(OpenAiResponsesProvider.openai(config()))
                                .structuredOutput("gpt-4o"))
                .isEqualTo(StructuredOutput.JSON_SCHEMA);
        assertThat(
                        new DefaultLLMClient(OpenAiResponsesProvider.openai(config()))
                                .structuredOutput("gpt-3.5-turbo"))
                .isEqualTo(StructuredOutput.NONE);
        assertThat(
                        new DefaultLLMClient(OpenAiResponsesProvider.xai(config()))
                                .structuredOutput("grok-3"))
                .isEqualTo(StructuredOutput.NONE);
        assertThat(
                        new DefaultLLMClient(OpenAiResponsesProvider.perplexity(config()))
                                .structuredOutput("openai/gpt-5-mini"))
                .isEqualTo(StructuredOutput.JSON_SCHEMA);
        assertThat(
                        new DefaultLLMClient(OpenAiResponsesProvider.perplexity(config()))
                                .structuredOutput(null))
                .isEqualTo(StructuredOutput.NONE);
    }
}
