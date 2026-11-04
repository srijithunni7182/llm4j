package io.github.llm4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ResponseFormat;
import io.github.llm4j.model.StructuredOutput;
import io.github.llm4j.provider.openai.OpenAiCompatibleProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The code samples in wiki/Structured-Output.md, compiled and run against a local server. Change a
 * sample here and in the page together; the last test fails if the page shows something else.
 */
class StructuredOutputDocsExamplesTest {

    private static final String CHAT =
            "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"vendor\\\":\\\"ACME GmbH\\\",\\\"total\\\":null}\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2,\"total_tokens\":11}}";

    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(CHAT));
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    @Test
    void askForAReplyThatFollowsASchema() throws Exception {
        LLMConfig cfg =
                LLMConfig.builder()
                        .apiKey("test-key")
                        .baseUrl(server.url("/v1").toString().replaceAll("/$", ""))
                        .defaultModel("gpt-4o-mini")
                        .build();
        // --- sample: schema
        LLMClient client = new DefaultLLMClient(OpenAiCompatibleProvider.openai(cfg));
        ResponseFormat invoice = ResponseFormat.jsonSchema("invoice", Map.of(
                "type", "object",
                "properties", Map.of(
                        "vendor", Map.of("type", "string"),
                        "total", Map.of("type", List.of("number", "null"))),   // optional: may be null
                "required", List.of("vendor", "total"),                      // strict mode wants every field listed
                "additionalProperties", false));

        LLMResponse reply = client.chat(LLMRequest.builder()
                .model("gpt-4o-mini")
                .addUserMessage("ACME GmbH invoice INV-8. Thank you for your business.")
                .responseFormat(invoice)                  // sent only if the model is recorded as supporting it
                .build());
        JsonNode fields = new ObjectMapper().readTree(reply.getContent());
        // fields.get("vendor") is a string; fields.get("total") is a number or null
        // --- end sample
        assertThat(fields.get("vendor").asText()).isEqualTo("ACME GmbH");
        assertThat(fields.get("total").isNull()).isTrue();
        JsonNode body = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(body.at("/response_format/json_schema/strict").asBoolean()).isTrue();
    }

    @Test
    void askWhetherAModelCanBeHeldToASchema() {
        LLMConfig cfg = LLMConfig.builder().apiKey("test-key").defaultModel("gpt-3.5-turbo").build();
        LLMClient client = new DefaultLLMClient(OpenAiCompatibleProvider.openai(cfg));
        // --- sample: capability
        StructuredOutput named = client.structuredOutput("gpt-4o");   // a model you name
        StructuredOutput configured = client.structuredOutput(null);  // null: the model this client is configured with
        // NONE means "not recorded as supported": ask for JSON in the prompt and check what comes back
        // --- end sample
        assertThat(named).isEqualTo(StructuredOutput.JSON_SCHEMA);
        assertThat(configured).isEqualTo(StructuredOutput.NONE);
    }

    @Test
    void thePageShowsExactlyTheSamplesThatAreTested() throws Exception {
        String source = Files.readString(Path.of("src/test/java/io/github/llm4j/StructuredOutputDocsExamplesTest.java"));
        String page = Files.readString(Path.of("wiki/Structured-Output.md"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("// --- sample: ([^\\n]+)\\n(.*?)\\n\\s*// --- end sample", java.util.regex.Pattern.DOTALL).matcher(source);
        int found = 0;
        while (m.find()) {
            found++;
            assertThat(page).as("the page shows the '" + m.group(1) + "' sample").contains(m.group(2).stripIndent().strip());
        }
        assertThat(found).isEqualTo(2);
    }
}
