package io.github.llm4j.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.ContentBlockedException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.LLMRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Anthropic-specific rules on top of the contract (verification plan C3 for sampling; R5). */
class AnthropicProviderTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static AnthropicProvider provider(String effort) {
        return new AnthropicProvider(LLMConfig.builder().apiKey("k").defaultModel("claude-opus-5-5").build(), null, effort);
    }

    static JsonNode body(LLMRequest request, String model, boolean stream, String effort) throws Exception {
        return JSON.readTree(provider(effort).body(request, model, stream));
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-haiku-4-5", "claude-sonnet-4-6", "claude-opus-4-6", "claude-sonnet-4-5-20250929",
            "claude-opus-4-1", "claude-opus-4", "claude-sonnet-4-20250514", "claude-3-7-sonnet-latest", "claude-3-5-haiku-20241022"})
    void olderModelsTakeSamplingSettings(String model) throws Exception {
        JsonNode b = body(LLMRequest.builder().addUserMessage("hi").temperature(0.7).topP(0.9).build(), model, false, null);
        assertThat(b.path("temperature").asDouble()).isEqualTo(0.7);
        assertThat(b.path("top_p").asDouble()).isEqualTo(0.9);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-opus-4-8",
            "claude-opus-4-7", "claude-sonnet-5", "claude-future-9"})
    void currentAndUnknownModelsNeverGetThem(String model) throws Exception {
        JsonNode b = body(LLMRequest.builder().addUserMessage("hi").temperature(0.7).topP(0.9).build(), model, false, null);
        assertThat(b.has("temperature")).isFalse();
        assertThat(b.has("top_p")).isFalse();
        assertThat(AnthropicModels.acceptsSampling(null)).isFalse();
    }

    @Test
    void maxTokensIsAlwaysSent() throws Exception {
        LLMRequest plain = LLMRequest.builder().addUserMessage("hi").build();
        assertThat(body(plain, "claude-opus-5-5", false, null).path("max_tokens").asInt()).isEqualTo(16_000);
        assertThat(body(plain, "claude-opus-5-5", true, null).path("max_tokens").asInt()).isEqualTo(64_000);
        assertThat(body(plain, "claude-opus-5-5", true, null).path("stream").asBoolean()).isTrue();
        assertThat(body(plain, "claude-opus-5-5", false, null).has("stream")).isFalse();
    }

    @Test
    void effortComesFromTheProviderOrTheRequest() throws Exception {
        LLMRequest plain = LLMRequest.builder().addUserMessage("hi").build();
        assertThat(body(plain, "claude-opus-5-5", false, null).has("output_config")).isFalse();
        assertThat(body(plain, "claude-opus-5-5", false, "high").path("output_config").path("effort").asText()).isEqualTo("high");
        LLMRequest asking = LLMRequest.builder().addUserMessage("hi").addParameter("effort", "low").build();
        assertThat(body(asking, "claude-opus-5-5", false, "high").path("output_config").path("effort").asText()).isEqualTo("low");
        LLMRequest bad = LLMRequest.builder().addUserMessage("hi").addParameter("effort", "extreme").build();
        assertThatThrownBy(() -> body(bad, "claude-opus-5-5", false, null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> provider("extreme")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consecutiveTurnsOfOneRoleAreMergedAndSystemIsTopLevel() throws Exception {
        JsonNode b = body(LLMRequest.builder().addSystemMessage("Be brief.").addUserMessage("Part one.").addUserMessage("Part two.")
                .addAssistantMessage("Noted.").addUserMessage("Go.").build(), "claude-opus-5-5", false, null);
        assertThat(b.path("system").asText()).isEqualTo("Be brief.");
        assertThat(b.path("messages")).hasSize(3);
        assertThat(b.path("messages").get(0).path("content").asText()).isEqualTo("Part one.\n\nPart two.");
        assertThat(b.path("messages").get(1).path("role").asText()).isEqualTo("assistant");
        JsonNode none = body(LLMRequest.builder().addUserMessage("hi").stopSequences(List.of()).build(), "claude-opus-5-5", false, null);
        assertThat(none.has("system")).isFalse();
        assertThat(none.has("stop_sequences")).isFalse();
    }

    @Test
    void refusalsNameTheirCategoryAndUsageCountsTheCache() throws Exception {
        AnthropicProvider p = provider(null);
        assertThatThrownBy(() -> p.parse(JSON.readTree("""
                {"type":"message","content":[],"stop_reason":"refusal","stop_details":{"type":"refusal","category":"bio","explanation":"Not able to help."}}"""), "m"))
                .isInstanceOf(ContentBlockedException.class).hasMessageContaining("refused (bio): Not able to help.");
        assertThatThrownBy(() -> p.parse(JSON.readTree("{\"type\":\"message\",\"content\":[],\"stop_reason\":\"refusal\",\"stop_details\":{\"category\":null}}"), "m"))
                .hasMessageContaining("refused (unspecified)");
        var r = p.parse(JSON.readTree("""
                {"id":"msg_x","type":"message","model":"claude-opus-5-5","content":[{"type":"redacted_thinking","data":"…"},{"type":"text","text":"a"},{"type":"text","text":"b"}],
                 "stop_reason":"end_turn","usage":{"input_tokens":10,"cache_creation_input_tokens":100,"cache_read_input_tokens":1000,"output_tokens":5}}"""), "m");
        assertThat(r.getContent()).isEqualTo("ab");
        assertThat(r.getTokenUsage().getPromptTokens()).isEqualTo(1110);
        assertThat(r.getMetadata()).containsEntry("id", "msg_x");
    }

    @Test
    void aKeyAndAModelAreRequired() {
        assertThatThrownBy(() -> new AnthropicProvider(LLMConfig.builder().build())).isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
        AnthropicProvider noModel = new AnthropicProvider(LLMConfig.builder().apiKey("k").build(), null, null);
        assertThatThrownBy(() -> noModel.chat(LLMRequest.builder().addUserMessage("hi").build()))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("Model must be specified");
        assertThat(noModel.getProviderName()).isEqualTo("anthropic");
    }
}
