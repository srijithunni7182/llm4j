package io.github.llm4j.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.ResponseFormat;
import io.github.llm4j.model.StructuredOutput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The response format on the Messages API (docs/specs/30-native-json-output, R4): {@code
 * output_config.format} with type {@code json_schema}, for the models the documentation lists.
 */
class StructuredOutputBodyTest {

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

    private static AnthropicProvider provider() {
        return new AnthropicProvider(LLMConfig.builder().apiKey("test-key").build());
    }

    private static JsonNode body(String model, LLMRequest.Builder request) throws Exception {
        return JSON.readTree(provider().body(request.model(model).build(), model, false));
    }

    private static LLMRequest.Builder ask() {
        return LLMRequest.builder().addUserMessage("hi").responseFormat(FORMAT);
    }

    @Test
    void aSupportedModelGetsTheFormatUnderOutputConfig() throws Exception {
        JsonNode body = body("claude-sonnet-5-5", ask());

        JsonNode format = body.at("/output_config/format");
        assertThat(format.get("type").asText()).isEqualTo("json_schema");
        assertThat(format.at("/schema/additionalProperties").asBoolean()).isFalse();
        assertThat(format.at("/schema/required/0").asText()).isEqualTo("vendor");
        // the Messages API names no schema label and has no strict switch
        assertThat(format.has("name")).isFalse();
        assertThat(format.has("strict")).isFalse();
    }

    @Test
    void itSharesOutputConfigWithEffortInsteadOfReplacingIt() throws Exception {
        JsonNode body = body("claude-sonnet-5-5", ask().addParameter("effort", "low"));

        assertThat(body.at("/output_config/effort").asText()).isEqualTo("low");
        assertThat(body.at("/output_config/format/type").asText()).isEqualTo("json_schema");
    }

    @Test
    void aModelNobodyRecordedGetsNoFormat() throws Exception {
        JsonNode body = body("claude-3-5-sonnet-20241022", ask());

        assertThat(body.has("output_config")).isFalse();
    }

    @Test
    void aRequestWithoutAFormatIsUnchanged() throws Exception {
        JsonNode body = body("claude-sonnet-5-5", LLMRequest.builder().addUserMessage("hi"));

        assertThat(body.has("output_config")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "claude-opus-5-5,JSON_SCHEMA",
        "claude-opus-5,JSON_SCHEMA",
        "claude-sonnet-5-5,JSON_SCHEMA",
        "claude-sonnet-5,JSON_SCHEMA",
        "claude-haiku-5-5,JSON_SCHEMA",
        "claude-fable-5-1,JSON_SCHEMA",
        "claude-fable-5,JSON_SCHEMA",
        "claude-mythos-5-1,JSON_SCHEMA",
        "claude-mythos-5,JSON_SCHEMA",
        "claude-mythos-preview,JSON_SCHEMA",
        "claude-haiku-4-5-20251001,JSON_SCHEMA",
        "claude-opus-4-8,JSON_SCHEMA",
        "claude-opus-4-7,JSON_SCHEMA",
        "claude-opus-4-6,JSON_SCHEMA",
        "claude-sonnet-4-6,JSON_SCHEMA",
        "claude-opus-4-5-20251101,JSON_SCHEMA",
        "claude-sonnet-4-5-20250929,JSON_SCHEMA",
        "claude-3-5-sonnet-20241022,NONE",
        "claude-opus-4-1-20250805,NONE",
        "claude-sonnet-4-20250514,NONE",
        "claude-something-new,NONE"
    })
    void theCapabilityIsTheListInTheDocs(String model, StructuredOutput expected) {
        assertThat(provider().structuredOutput(model)).isEqualTo(expected);
    }

    @Test
    void nullMeansTheConfiguredModel() {
        AnthropicProvider configured =
                new AnthropicProvider(
                        LLMConfig.builder()
                                .apiKey("test-key")
                                .defaultModel("claude-opus-5-5")
                                .build());

        assertThat(configured.structuredOutput(null)).isEqualTo(StructuredOutput.JSON_SCHEMA);
        assertThat(provider().structuredOutput(null)).isEqualTo(StructuredOutput.NONE);
    }
}
