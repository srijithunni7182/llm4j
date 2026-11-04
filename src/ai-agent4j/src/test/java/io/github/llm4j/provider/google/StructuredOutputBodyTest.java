package io.github.llm4j.provider.google;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The response format on {@code generateContent} (docs/specs/30-native-json-output, R4): {@code
 * generationConfig.responseMimeType} of {@code application/json} and the schema in {@code
 * responseJsonSchema}, which takes full JSON Schema (nullable as a type list). Confirmed against
 * the real API on 2026-11-04 (LIVE_FIXTURES.md).
 */
class StructuredOutputBodyTest {

    private static final String REPLY =
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]},\"finishReason\":\"STOP\"}],"
                    + "\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1,\"totalTokenCount\":2}}";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ResponseFormat FORMAT =
            ResponseFormat.jsonSchema(
                    "invoice",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("total", Map.of("type", List.of("number", "null"))),
                            "required",
                            List.of("total"),
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

    private GoogleProvider provider() {
        return new GoogleProvider(
                LLMConfig.builder()
                        .apiKey("test-key")
                        .baseUrl(server.url("/v1beta").toString().replaceAll("/$", ""))
                        .defaultModel("gemini-2.5-flash")
                        .build());
    }

    private JsonNode send(String model, boolean withFormat) throws Exception {
        LLMRequest.Builder request = LLMRequest.builder().addUserMessage("hi").model(model);
        if (withFormat) request.responseFormat(FORMAT);
        provider().chat(request.build());
        return JSON.readTree(server.takeRequest().getBody().readUtf8());
    }

    @Test
    void aSupportedModelGetsTheMimeTypeAndTheFullJsonSchema() throws Exception {
        JsonNode config = send("gemini-2.5-flash", true).get("generationConfig");

        assertThat(config.get("responseMimeType").asText()).isEqualTo("application/json");
        JsonNode schema = config.get("responseJsonSchema");
        assertThat(schema.at("/properties/total/type/1").asText()).isEqualTo("null");
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
        assertThat(config.has("responseSchema")).isFalse();
    }

    @Test
    void aModelNobodyRecordedGetsNoFormat() throws Exception {
        JsonNode config = send("gemini-2.0-flash", true).get("generationConfig");

        assertThat(config.has("responseMimeType")).isFalse();
        assertThat(config.has("responseJsonSchema")).isFalse();
    }

    @Test
    void aRequestWithoutAFormatIsUnchanged() throws Exception {
        JsonNode config = send("gemini-2.5-flash", false).get("generationConfig");

        assertThat(config.has("responseMimeType")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "gemini-2.5-flash,JSON_SCHEMA",
        "gemini-2.5-flash-lite,JSON_SCHEMA",
        "gemini-2.5-pro,JSON_SCHEMA",
        "gemini-3.7-flash,JSON_SCHEMA",
        "gemini-3.8-flash,JSON_SCHEMA",
        "gemini-2.0-flash,NONE",
        "gemini-1.5-pro,NONE",
        "gemini-pro,NONE",
        "something-else,NONE"
    })
    void theCapabilityIsGemini25AndLater(String model, StructuredOutput expected) {
        assertThat(provider().structuredOutput(model)).isEqualTo(expected);
    }

    @Test
    void nullMeansTheConfiguredModel() {
        assertThat(provider().structuredOutput(null)).isEqualTo(StructuredOutput.JSON_SCHEMA);
    }
}
