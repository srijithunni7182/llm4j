package io.github.llm4j.provider.ollama;

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

/**
 * The response format on Ollama's chat API (docs/specs/30-native-json-output, R4): the schema goes
 * in {@code format}. A local server constrains whatever model it runs; Ollama Cloud documents that
 * it does not support structured outputs, so it is left on the prompt route.
 */
class StructuredOutputBodyTest {

    private static final String REPLY =
            "{\"model\":\"llama3.2\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"done\":true,"
                    + "\"done_reason\":\"stop\",\"prompt_eval_count\":1,\"eval_count\":1}";
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

    private OllamaProvider provider(String baseUrl) {
        return new OllamaProvider(
                LLMConfig.builder().baseUrl(baseUrl).defaultModel("llama3.2").build());
    }

    @Test
    void theSchemaGoesInTheFormatField() throws Exception {
        OllamaProvider provider = provider(server.url("/api").toString().replaceAll("/$", ""));

        provider.chat(LLMRequest.builder().addUserMessage("hi").responseFormat(FORMAT).build());

        JsonNode body = JSON.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(body.at("/format/type").asText()).isEqualTo("object");
        assertThat(body.at("/format/required/0").asText()).isEqualTo("vendor");
        assertThat(body.at("/format/additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void aRequestWithoutAFormatIsUnchanged() throws Exception {
        OllamaProvider provider = provider(server.url("/api").toString().replaceAll("/$", ""));

        provider.chat(LLMRequest.builder().addUserMessage("hi").build());

        assertThat(JSON.readTree(server.takeRequest().getBody().readUtf8()).has("format"))
                .isFalse();
    }

    @Test
    void aLocalServerIsCapableForWhateverModelItRuns() {
        assertThat(provider("http://localhost:11434/api").structuredOutput("anything-local"))
                .isEqualTo(StructuredOutput.JSON_SCHEMA);
        assertThat(provider("http://192.168.1.20:11434/api").structuredOutput(null))
                .isEqualTo(StructuredOutput.JSON_SCHEMA);
    }

    @Test
    void ollamaCloudIsLeftOnThePromptRoute() throws Exception {
        assertThat(provider("https://ollama.com/api").structuredOutput("gpt-oss:120b"))
                .isEqualTo(StructuredOutput.NONE);
        assertThat(provider("https://api.ollama.com/api").structuredOutput("x"))
                .isEqualTo(StructuredOutput.NONE);
    }

    @Test
    void aCloudHostThatMerelyContainsTheNameIsNotMistakenForIt() {
        assertThat(provider("http://my-ollama.company.net:11434/api").structuredOutput("x"))
                .isEqualTo(StructuredOutput.JSON_SCHEMA);
    }
}
