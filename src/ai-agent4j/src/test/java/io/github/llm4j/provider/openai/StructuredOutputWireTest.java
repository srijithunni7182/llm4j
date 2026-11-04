package io.github.llm4j.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.ResponseFormat;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The exact request body for a response format (docs/specs/30-native-json-output, R4): the nested
 * {@code response_format.json_schema} form of chat completions, sent only to a model that is known
 * to accept it.
 */
class StructuredOutputWireTest {

    private static final String CHAT =
            "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
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
                            java.util.List.of("vendor"),
                            "additionalProperties",
                            false));

    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        server.enqueue(
                new MockResponse().setHeader("Content-Type", "application/json").setBody(CHAT));
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private LLMClient client(boolean xai) {
        LLMConfig config =
                LLMConfig.builder()
                        .apiKey("test-key")
                        .baseUrl(server.url("/v1").toString().replaceAll("/$", ""))
                        .build();
        return new DefaultLLMClient(
                xai
                        ? OpenAiCompatibleProvider.xai(config)
                        : OpenAiCompatibleProvider.openai(config));
    }

    private JsonNode send(LLMClient client, String model, ResponseFormat format) throws Exception {
        LLMRequest.Builder request = LLMRequest.builder().addUserMessage("hi").model(model);
        if (format != null) request.responseFormat(format);
        client.chat(request.build());
        return JSON.readTree(server.takeRequest().getBody().readUtf8());
    }

    @Test
    void aSupportedModelGetsTheNestedJsonSchemaForm() throws Exception {
        JsonNode body = send(client(false), "gpt-4o", FORMAT);

        JsonNode format = body.get("response_format");
        assertThat(format.get("type").asText()).isEqualTo("json_schema");
        assertThat(format.at("/json_schema/name").asText()).isEqualTo("invoice");
        assertThat(format.at("/json_schema/strict").asBoolean()).isTrue();
        assertThat(format.at("/json_schema/schema/additionalProperties").asBoolean()).isFalse();
        assertThat(format.at("/json_schema/schema/required/0").asText()).isEqualTo("vendor");
    }

    @Test
    void xaiGetsTheSameFormOnAGrok4Model() throws Exception {
        JsonNode body = send(client(true), "grok-4", FORMAT);

        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(body.at("/response_format/json_schema/name").asText()).isEqualTo("invoice");
    }

    @Test
    void aModelNobodyRecordedGetsNoFormat() throws Exception {
        JsonNode body = send(client(false), "gpt-3.5-turbo", FORMAT);

        assertThat(body.has("response_format")).isFalse();
    }

    @Test
    void aRequestWithoutAFormatIsUnchanged() throws Exception {
        JsonNode body = send(client(false), "gpt-4o", null);

        assertThat(body.has("response_format")).isFalse();
    }

    @Test
    void theEscapeHatchStillWinsWhenTheCallerSetsResponseFormatByHand() throws Exception {
        LLMRequest request =
                LLMRequest.builder()
                        .addUserMessage("hi")
                        .model("gpt-4o")
                        .responseFormat(FORMAT)
                        .addParameter("response_format", Map.of("type", "json_object"))
                        .build();
        client(false).chat(request);

        JsonNode body = JSON.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_object");
    }
}
