package io.github.llm4j.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolSchema;
import io.github.llm4j.model.ToolSpec;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.ollama.OllamaProvider;
import io.github.llm4j.provider.sarvam.SarvamChatProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

/**
 * R5, acceptance A7: a request that does not ask for grounding produces the very bytes the previous
 * release produced, for every provider that existed before grounding (Gemini, Anthropic, Sarvam,
 * Ollama). The expected bytes were recorded from this code <b>before</b> grounding was added
 * ({@code src/test/resources/request-bytes}); to re-record deliberately, run with {@code
 * -Dregen=true} and review the diff.
 */
class RequestBytesRegressionTest {

    private static final ToolSpec WEATHER =
            new ToolSpec(
                    "get_weather",
                    "Weather for a city",
                    ToolSchema.object().string("city", "the city", true).build());

    private static Map<String, LLMRequest> cases() {
        Map<String, LLMRequest> cases = new LinkedHashMap<>();
        cases.put("plain", LLMRequest.builder().addUserMessage("Hi there").build());
        cases.put(
                "settings",
                LLMRequest.builder()
                        .addSystemMessage("You are terse.")
                        .addUserMessage("Hi")
                        .maxTokens(100)
                        .temperature(0.3)
                        .topP(0.9)
                        .stopSequences(List.of("END"))
                        .build());
        cases.put(
                "multiturn",
                LLMRequest.builder()
                        .addUserMessage("My name is Asha")
                        .addAssistantMessage("Hi Asha")
                        .addUserMessage("What is my name?")
                        .build());
        cases.put(
                "tools",
                LLMRequest.builder()
                        .addUserMessage("weather in Pune?")
                        .tools(List.of(WEATHER))
                        .build());
        cases.put(
                "tool-turn",
                LLMRequest.builder()
                        .addUserMessage("weather in Pune?")
                        .addMessage(
                                Message.assistantToolCalls(
                                        "",
                                        List.of(
                                                new ToolCall(
                                                        "call_1",
                                                        "get_weather",
                                                        Map.of("city", "Pune"))),
                                        Map.of()))
                        .addMessage(Message.toolResult("call_1", "get_weather", "{\"temp\":31}"))
                        .tools(List.of(WEATHER))
                        .build());
        cases.put(
                "reasoning",
                LLMRequest.builder().addUserMessage("Think about it").reasoning(true).build());
        return cases;
    }

    private record Vendor(String name, Function<LLMConfig, LLMProvider> provider, String model) {}

    private static List<Vendor> vendors() {
        return List.of(
                new Vendor("google", GoogleProvider::new, "gemini-2.5-flash"),
                new Vendor("anthropic", AnthropicProvider::new, "claude-haiku-4-5"),
                new Vendor("sarvam", SarvamChatProvider::new, "sarvam-m"),
                new Vendor("ollama", OllamaProvider::new, "llama3"));
    }

    /**
     * What went over the wire: the method and path, the content headers, the body. The key is not
     * part of it.
     */
    private static String sent(Vendor v, LLMRequest request, boolean stream) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            LLMConfig config =
                    LLMConfig.builder()
                            .apiKey("k-1234567")
                            .baseUrl(server.url("/").toString().replaceAll("/$", ""))
                            .defaultModel(v.model())
                            .retryPolicy(RetryPolicy.builder().maxRetries(0).build())
                            .build();
            LLMProvider provider = v.provider().apply(config);
            try {
                if (stream) provider.chatStream(request).toList();
                else provider.chat(request);
            } catch (RuntimeException ignored) {
                // the canned answer is not an answer; only the request is compared
            }
            RecordedRequest r = server.takeRequest();
            StringBuilder out =
                    new StringBuilder(r.getMethod()).append(' ').append(r.getPath()).append('\n');
            for (String h : List.of("content-type", "anthropic-version")) {
                if (r.getHeader(h) != null)
                    out.append(h).append(": ").append(r.getHeader(h)).append('\n');
            }
            return out.append('\n').append(r.getBody().readUtf8()).append('\n').toString();
        }
    }

    @Test
    void aRequestWithoutGroundingIsByteIdenticalToThePreviousRelease() throws Exception {
        boolean regen = Boolean.getBoolean("regen");
        int compared = 0;
        for (Vendor v : vendors()) {
            for (Map.Entry<String, LLMRequest> c : cases().entrySet()) {
                for (boolean stream : new boolean[] {false, true}) {
                    String name = v.name() + "/" + c.getKey() + (stream ? "-stream" : "") + ".txt";
                    Path file = Path.of("src/test/resources/request-bytes").resolve(name);
                    String actual = sent(v, c.getValue(), stream);
                    if (regen) {
                        Files.createDirectories(file.getParent());
                        Files.writeString(file, actual, StandardCharsets.UTF_8);
                    }
                    assertThat(file).as("the recorded request " + name).exists();
                    assertThat(actual).as(name).isEqualTo(readUtf8(file));
                    compared++;
                }
            }
        }
        assertThat(compared).isEqualTo(48);
    }

    private static String readUtf8(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
