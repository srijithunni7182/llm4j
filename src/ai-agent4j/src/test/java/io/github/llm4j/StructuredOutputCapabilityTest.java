package io.github.llm4j;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.StructuredOutput;
import io.github.llm4j.privacy.MaskingLLMClient;
import io.github.llm4j.provider.openai.OpenAiCompatibleProvider;
import io.github.llm4j.routing.RoutingLLMClient;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What a client says about schema-constrained JSON (docs/specs/30-native-json-output, R2): from
 * data per vendor and model, unknown means no, and a wrapper says what it wraps.
 */
class StructuredOutputCapabilityTest {

    private static LLMClient openai() {
        return new DefaultLLMClient(
                OpenAiCompatibleProvider.openai(LLMConfig.builder().apiKey("test-key").build()));
    }

    private static LLMClient xai() {
        return new DefaultLLMClient(
                OpenAiCompatibleProvider.xai(LLMConfig.builder().apiKey("test-key").build()));
    }

    @ParameterizedTest
    @CsvSource({
        "gpt-4o,JSON_SCHEMA",
        "gpt-4o-mini,JSON_SCHEMA",
        "gpt-4.1,JSON_SCHEMA",
        "gpt-5-mini,JSON_SCHEMA",
        "o3,JSON_SCHEMA",
        "gpt-3.5-turbo,NONE",
        "gpt-4-turbo,NONE",
        "gpt-oss-20b,NONE",
        "a-model-nobody-recorded,NONE"
    })
    void openAiSaysYesOnlyForModelsItsDocsName(String model, StructuredOutput expected) {
        assertThat(openai().structuredOutput(model)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"grok-4,JSON_SCHEMA", "grok-4-fast,JSON_SCHEMA", "grok-3,NONE", "unknown,NONE"})
    void xaiSaysYesOnlyForTheGrok4Family(String model, StructuredOutput expected) {
        assertThat(xai().structuredOutput(model)).isEqualTo(expected);
    }

    @Test
    void nullMeansTheModelTheClientIsConfiguredWith() {
        LLMClient configured =
                new DefaultLLMClient(
                        OpenAiCompatibleProvider.openai(
                                LLMConfig.builder()
                                        .apiKey("test-key")
                                        .defaultModel("gpt-4o")
                                        .build()));
        LLMClient legacy =
                new DefaultLLMClient(
                        OpenAiCompatibleProvider.openai(
                                LLMConfig.builder()
                                        .apiKey("test-key")
                                        .defaultModel("gpt-3.5-turbo")
                                        .build()));

        assertThat(configured.structuredOutput(null)).isEqualTo(StructuredOutput.JSON_SCHEMA);
        assertThat(legacy.structuredOutput(null)).isEqualTo(StructuredOutput.NONE);
    }

    @Test
    void presetVendorsWithoutRecordedFixturesStayOff() {
        LLMConfig config = LLMConfig.builder().apiKey("test-key").build();
        assertThat(
                        new DefaultLLMClient(OpenAiCompatibleProvider.kimi(config))
                                .structuredOutput("kimi-k3"))
                .isEqualTo(StructuredOutput.NONE);
        assertThat(
                        new DefaultLLMClient(OpenAiCompatibleProvider.qwen(config))
                                .structuredOutput("qwen3.8-max"))
                .isEqualTo(StructuredOutput.NONE);
    }

    @Test
    void aClientThatSaysNothingSaysNo() {
        LLMClient plain =
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        return LLMResponse.builder().content("x").build();
                    }

                    @Override
                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                        return Stream.empty();
                    }
                };
        assertThat(plain.structuredOutput("gpt-4o")).isEqualTo(StructuredOutput.NONE);
    }

    @Test
    void maskingAndBudgetClientsSayWhatTheyWrap() {
        assertThat(new MaskingLLMClient(openai()).structuredOutput("gpt-4o"))
                .isEqualTo(StructuredOutput.JSON_SCHEMA);
        assertThat(new MaskingLLMClient(openai()).structuredOutput("gpt-3.5-turbo"))
                .isEqualTo(StructuredOutput.NONE);
        assertThat(BudgetedLLMClient.builder(openai()).build().structuredOutput("gpt-4o"))
                .isEqualTo(StructuredOutput.JSON_SCHEMA);
    }

    @Test
    void routingSaysYesOnlyWhenEveryClientItMayUseSaysYes() {
        RoutingLLMClient both =
                RoutingLLMClient.builder()
                        .addClient(io.github.llm4j.routing.ProviderTier.BALANCED, openai())
                        .addClient(io.github.llm4j.routing.ProviderTier.BALANCED, xai())
                        .build();
        assertThat(both.structuredOutput("grok-4")).isEqualTo(StructuredOutput.NONE);
        RoutingLLMClient one =
                RoutingLLMClient.builder()
                        .addClient(io.github.llm4j.routing.ProviderTier.BALANCED, openai())
                        .build();
        assertThat(one.structuredOutput("gpt-4o")).isEqualTo(StructuredOutput.JSON_SCHEMA);
    }
}
