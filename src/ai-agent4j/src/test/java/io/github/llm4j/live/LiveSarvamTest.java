package io.github.llm4j.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ResponseFormat;
import io.github.llm4j.provider.openai.OpenAiCompatibleProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What only Sarvam's V2 chat completions does, against the real API: the model listing under {@code
 * /v2}, a JSON-Schema answer, a refused key, and thinking switched off by {@code reasoning_effort:
 * "none"}. Needs {@code SARVAM_API_KEY}; tagged {@code live}, so it never runs in a default build
 * ({@code mvn -pl src/ai-agent4j -Plive test}).
 */
@Tag("live")
class LiveSarvamTest {

    private static String key() {
        String key = LiveTargets.env("SARVAM_API_KEY");
        assumeTrue(key != null, "SARVAM_API_KEY not set");
        return key;
    }

    private static OpenAiCompatibleProvider provider(String key, String model) {
        return OpenAiCompatibleProvider.sarvam(LiveTargets.config(key, null, model));
    }

    @Test
    void theListingNamesSarvamsOwnAndTheTokenFactorysModels() {
        assertThat(provider(key(), "sarvam-105b").listModels())
                .contains("sarvam-105b", "gemma4", "glm5.3");
    }

    @Test
    void aSchemaConstrainedAnswerParses() throws Exception {
        LLMResponse r =
                provider(key(), "deepseekv4.1-flash-1017")
                        .chat(
                                LLMRequest.builder()
                                        .addUserMessage("Which city is the capital of India?")
                                        .maxTokens(200)
                                        .responseFormat(
                                                ResponseFormat.jsonSchema(
                                                        "capital",
                                                        Map.of(
                                                                "type",
                                                                "object",
                                                                "properties",
                                                                Map.of(
                                                                        "city",
                                                                        Map.of("type", "string")),
                                                                "required",
                                                                List.of("city"),
                                                                "additionalProperties",
                                                                false)))
                                        .build());
        assertThat(new ObjectMapper().readTree(r.getContent()).path("city").asText())
                .containsIgnoringCase("delhi");
    }

    @Test
    void aWrongKeyIsAnAuthenticationFailure() {
        assertThatThrownBy(
                        () ->
                                provider("sk-test-000", "sarvam-105b")
                                        .chat(LLMRequest.builder().addUserMessage("hi").build()))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void sarvam105bAnswersWithoutThinkingUnlessAsked() {
        LLMResponse r =
                provider(key(), "sarvam-105b")
                        .chat(
                                LLMRequest.builder()
                                        .addUserMessage("Reply with the single word: ok")
                                        .maxTokens(50)
                                        .build());
        assertThat(r.getContent()).containsIgnoringCase("ok");
    }
}
