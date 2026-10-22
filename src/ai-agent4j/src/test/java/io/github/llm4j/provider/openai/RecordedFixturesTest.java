package io.github.llm4j.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.LLMResponse.FinishReason;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCallDelta;
import io.github.llm4j.model.ToolSchema;
import io.github.llm4j.model.ToolSpec;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.fixtures.Fixtures;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Replays the responses recorded from the real APIs (by {@code ProviderSmokeIntegrationTest})
 * through each provider. They are what makes a provider "supported" rather than "preview": the wire
 * formats here are the vendors', not ours. Every fixture states its date and model.
 */
class RecordedFixturesTest {

    /** One recorded vendor: its fixture folder and the provider that must read it. */
    record Vendor(String directory, Function<LLMConfig, LLMProvider> provider, String model) {
        @Override
        public String toString() {
            return directory;
        }
    }

    static List<Vendor> vendors() {
        return List.of(
                new Vendor(
                        "openai",
                        c -> new OpenAiCompatibleProvider(Dialect.OPENAI, c),
                        "gpt-6-luna"),
                new Vendor(
                        "openai-responses",
                        c -> new OpenAiResponsesProvider(ResponsesDialect.OPENAI, c),
                        "gpt-6-luna"),
                new Vendor(
                        "xai",
                        c -> new OpenAiCompatibleProvider(Dialect.XAI, c),
                        "grok-4.20-0309-non-reasoning"),
                new Vendor(
                        "perplexity-agent",
                        c -> new OpenAiResponsesProvider(ResponsesDialect.PERPLEXITY, c),
                        "openai/gpt-6-luna"));
    }

    private static final ToolSpec WEATHER =
            new ToolSpec(
                    "get_weather",
                    "Current weather for a city",
                    ToolSchema.object().string("city", "the city", true).build());

    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private LLMProvider provider(Vendor v) {
        return v.provider()
                .apply(
                        LLMConfig.builder()
                                .apiKey("k-1234567")
                                .baseUrl(server.url("/v1").toString())
                                .defaultModel(v.model())
                                .build());
    }

    private void enqueue(Vendor v, String name, String type) {
        Fixtures f = Fixtures.load(v.directory() + "/" + name + ".fixture");
        assertThat(f.source())
                .as(name + " must be recorded from the real API")
                .isEqualTo("recorded");
        server.enqueue(new MockResponse().setHeader("Content-Type", type).setBody(f.body()));
    }

    private static String text(List<LLMResponse> chunks) {
        return chunks.stream()
                .map(LLMResponse::getContent)
                .filter(c -> c != null)
                .collect(Collectors.joining());
    }

    @ParameterizedTest
    @MethodSource("vendors")
    void aPlainAnswer(Vendor v) {
        enqueue(v, "plain", "application/json");
        LLMResponse r =
                provider(v)
                        .chat(
                                LLMRequest.builder()
                                        .addUserMessage("Reply with exactly the word: ready")
                                        .build());
        assertThat(r.getContent()).isEqualToIgnoringCase("ready");
        assertThat(r.getTokenUsage().getTotalTokens()).isPositive();
        assertThat(r.getFinishReason()).isEqualTo(FinishReason.STOP);
        assertThat(r.getModel()).isNotBlank();
    }

    @ParameterizedTest
    @MethodSource("vendors")
    void aStreamedAnswerEndsWithUsageAndAFinishReason(Vendor v) {
        enqueue(v, "stream", "text/event-stream");
        List<LLMResponse> chunks =
                provider(v)
                        .chatStream(
                                LLMRequest.builder()
                                        .addUserMessage("Reply with exactly the word: ready")
                                        .build())
                        .toList();
        assertThat(text(chunks)).isEqualToIgnoringCase("ready");
        LLMResponse last = chunks.get(chunks.size() - 1);
        assertThat(last.getTokenUsage().getTotalTokens()).isPositive();
        assertThat(last.getFinishReason()).isEqualTo(FinishReason.STOP);
        assertThat(chunks).filteredOn(c -> c.getFinishReason() != FinishReason.UNKNOWN).hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("vendors")
    void aToolCallAndTheAnswerAfterTheToolResult(Vendor v) {
        enqueue(v, "tool-1", "application/json");
        LLMProvider p = provider(v);
        LLMResponse first =
                p.chat(
                        LLMRequest.builder()
                                .addUserMessage(
                                        "What is the weather in Pune right now? Use the get_weather tool.")
                                .tools(List.of(WEATHER))
                                .build());
        assertThat(first.getFinishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(first.getToolCalls())
                .singleElement()
                .satisfies(
                        call -> {
                            assertThat(call.name()).isEqualTo("get_weather");
                            assertThat(String.valueOf(call.arguments().get("city")))
                                    .containsIgnoringCase("pune");
                            assertThat(call.id()).isNotBlank();
                        });
        enqueue(v, "tool-2", "application/json");
        LLMResponse second =
                p.chat(
                        LLMRequest.builder()
                                .addUserMessage(
                                        "What is the weather in Pune right now? Use the get_weather tool.")
                                .addMessage(
                                        Message.assistantToolCalls(
                                                first.getContent(),
                                                first.getToolCalls(),
                                                first.getProviderData()))
                                .addMessage(
                                        Message.toolResult(
                                                first.getToolCalls().get(0).id(),
                                                "get_weather",
                                                "{\"temperature_c\":31,\"sky\":\"clear\"}"))
                                .tools(List.of(WEATHER))
                                .build());
        assertThat(second.getContent()).contains("31");
        assertThat(second.getToolCalls()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("vendors")
    void aStreamedToolCallAssemblesFromItsDeltas(Vendor v) {
        enqueue(v, "tool-stream", "text/event-stream");
        List<LLMResponse> chunks =
                provider(v)
                        .chatStream(
                                LLMRequest.builder()
                                        .addUserMessage(
                                                "What is the weather in Pune right now? Use the get_weather tool.")
                                        .tools(List.of(WEATHER))
                                        .build())
                        .toList();
        List<ToolCallDelta> deltas =
                chunks.stream().map(LLMResponse::getToolCallDelta).filter(d -> d != null).toList();
        assertThat(deltas.get(0).name()).isEqualTo("get_weather");
        assertThat(deltas.get(0).id()).isNotBlank();
        assertThat(
                        deltas.stream()
                                .map(ToolCallDelta::argumentsFragment)
                                .collect(Collectors.joining()))
                .containsIgnoringCase("pune");
        assertThat(chunks.get(chunks.size() - 1).getFinishReason())
                .isEqualTo(FinishReason.TOOL_CALLS);
    }

    @ParameterizedTest
    @MethodSource("vendors")
    void aReasoningCallKeepsReasoningOutOfTheAnswer(Vendor v) {
        enqueue(v, "reasoning-stream", "text/event-stream");
        List<LLMResponse> chunks =
                provider(v)
                        .chatStream(
                                LLMRequest.builder()
                                        .addUserMessage(
                                                "What is 17 * 23? Think it through, then give the number.")
                                        .reasoning(true)
                                        .build())
                        .toList();
        String answer = text(chunks);
        String thought =
                chunks.stream()
                        .map(LLMResponse::getReasoning)
                        .filter(c -> c != null)
                        .collect(Collectors.joining());
        assertThat(answer).contains("391");
        if (!thought.isEmpty()) assertThat(answer).doesNotContain(thought);
        assertThat(chunks.get(chunks.size() - 1).getFinishReason()).isEqualTo(FinishReason.STOP);
    }
}
