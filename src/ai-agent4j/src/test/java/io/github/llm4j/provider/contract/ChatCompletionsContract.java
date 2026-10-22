package io.github.llm4j.provider.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.exception.ContentBlockedException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.LLMResponse.FinishReason;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolCallDelta;
import io.github.llm4j.model.ToolSchema;
import io.github.llm4j.model.ToolSpec;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.openai.Dialect;
import io.github.llm4j.provider.openai.OpenAiCompatibleProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

/**
 * The provider contract for every chat-completions dialect: the wire formats are the same, so a
 * dialect's test says only which {@link Dialect} and model it uses (and overrides the few places
 * where its vendor differs). On top of {@link ProviderContract} it checks tool calling, reasoning
 * text, and how odd responses are read (spec 08, R1, R3 and the fault-injection table).
 */
abstract class ChatCompletionsContract extends ProviderContract {

    /** The dialect under test. */
    protected abstract Dialect dialect();

    @Override
    protected LLMProvider provider(String model) {
        return new OpenAiCompatibleProvider(dialect(), config(url("/v1"), model));
    }

    @Override
    protected String nativeSystem(JsonNode request) {
        List<String> system = new ArrayList<>();
        request.path("messages")
                .forEach(
                        m -> {
                            if ("system".equals(m.path("role").asText()))
                                system.add(m.path("content").asText());
                        });
        return system.isEmpty() ? null : String.join("\n\n", system);
    }

    @Override
    protected List<String> turns(JsonNode request) {
        List<String> out = new ArrayList<>();
        request.path("messages")
                .forEach(
                        m -> {
                            if (!"system".equals(m.path("role").asText()))
                                out.add(
                                        m.path("role").asText()
                                                + ": "
                                                + m.path("content").asText());
                        });
        return out;
    }

    @Override
    protected Settings settings(JsonNode r) {
        return new Settings(
                integer(r.path(dialect().maxTokensField())),
                decimal(r.path("temperature")),
                decimal(r.path("top_p")),
                strings(r.path("stop")));
    }

    // ── wire formats ────────────────────────────────────────────────────────────────────────

    static String quote(String s) {
        return AnthropicContractTest.quote(s);
    }

    protected String completion(String reason, String text, int in, int out) {
        return """
                {"id":"chatcmpl-1","object":"chat.completion","model":"%s",
                 "choices":[{"index":0,"message":{"role":"assistant","content":%s},"finish_reason":"%s"}],
                 "usage":{"prompt_tokens":%d,"completion_tokens":%d,"total_tokens":%d}}"""
                .formatted(model(), quote(text), reason, in, out, in + out);
    }

    @Override
    protected String answer(String text, int in, int out) {
        return completion("stop", text, in, out);
    }

    @Override
    protected String truncated(String text) {
        return completion("length", text, 10, 5);
    }

    @Override
    protected String refusal(String category) {
        return """
                {"id":"chatcmpl-2","object":"chat.completion","model":"%s",
                 "choices":[{"index":0,"message":{"role":"assistant","content":null,"refusal":"I can't help with that (%s)."},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":5,"completion_tokens":9,"total_tokens":14}}"""
                .formatted(model(), category);
    }

    @Override
    protected String overflowMessage() {
        return "This model's maximum context length is 128000 tokens. However, your messages resulted in 130000 tokens. Please reduce the length of the messages.";
    }

    @Override
    protected String error(int status, String message) {
        return "{\"error\":{\"message\":"
                + quote(message)
                + ",\"type\":\"error_"
                + status
                + "\",\"param\":null,\"code\":null}}";
    }

    @Override
    protected MockResponse rateLimited(Instant reset) {
        return new MockResponse()
                .setResponseCode(429)
                .setHeader(
                        "Retry-After",
                        String.valueOf(Duration.between(Instant.now(), reset).getSeconds()))
                .setBody(error(429, "Rate limit reached"));
    }

    static String data(String json) {
        return "data: " + json + "\n\n";
    }

    static String delta(String text) {
        return "{\"choices\":[{\"index\":0,\"delta\":{\"content\":"
                + quote(text)
                + "},\"finish_reason\":null}]}";
    }

    @Override
    protected MockResponse stream(List<String> pieces, int in, int out) {
        StringBuilder sb =
                new StringBuilder(
                        data(
                                "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}"));
        pieces.forEach(p -> sb.append(data(delta(p))));
        sb.append(data("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"));
        // usage arrives in an extra chunk with no choices, as stream_options.include_usage makes
        // vendors send it
        sb.append(
                data(
                        "{\"choices\":[],\"usage\":{\"prompt_tokens\":"
                                + in
                                + ",\"completion_tokens\":"
                                + out
                                + ",\"total_tokens\":"
                                + (in + out)
                                + "}}"));
        sb.append(data("[DONE]"));
        return sse(sb.toString());
    }

    @Override
    protected MockResponse streamThenError(List<String> pieces) {
        StringBuilder sb = new StringBuilder();
        pieces.forEach(p -> sb.append(data(delta(p))));
        sb.append(data("{\"error\":{\"message\":\"internal error\"}}"));
        return sse(sb.toString());
    }

    @Override
    protected Class<? extends RuntimeException> midStreamError() {
        return ProviderException.class;
    }

    private static final ToolSpec WEATHER =
            new ToolSpec(
                    "get_weather",
                    "Weather for a city",
                    ToolSchema.object().string("city", "the city", true).build());

    private static String toolCallBody(String reasoning) {
        return """
                {"id":"chatcmpl-3","object":"chat.completion","model":"m",
                 "choices":[{"index":0,"message":{"role":"assistant","content":null,%s
                   "tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\\"city\\":\\"Pune\\"}"}}]},
                  "finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":20,"completion_tokens":8,"total_tokens":28}}"""
                .formatted(
                        reasoning == null ? "" : "\"reasoning_content\":" + quote(reasoning) + ",");
    }

    // ── where it talks, and as whom ─────────────────────────────────────────────────────────

    @Test
    void postsToTheChatPathWithABearerKey() throws Exception {
        server.enqueue(ok(answer("hi", 1, 1)));
        provider(model()).chat(ask("hi"));
        RecordedRequest r = server.takeRequest();
        assertThat(r.getMethod()).isEqualTo("POST");
        assertThat(r.getPath()).startsWith("/v1" + dialect().chatPath());
        assertThat(r.getHeader(dialect().authHeader())).isEqualTo(dialect().authScheme() + SECRET);
        assertThat(json.readTree(r.getBody().readUtf8()).path("model").asText()).isEqualTo(model());
    }

    @Test
    void theModelOfTheRequestWinsOverTheConfiguredOne() throws Exception {
        server.enqueue(ok(answer("hi", 1, 1)));
        provider(model())
                .chat(LLMRequest.builder().addUserMessage("hi").model("other-model").build());
        assertThat(sent().path("model").asText()).isEqualTo("other-model");
    }

    @Test
    void additionalParametersArePassedThroughForTheEscapeHatch() throws Exception {
        server.enqueue(ok(answer("hi", 1, 1)));
        provider(model())
                .chat(LLMRequest.builder().addUserMessage("hi").addParameter("seed", 7).build());
        assertThat(sent().path("seed").asInt()).isEqualTo(7);
    }

    // ── answers and odd answers ─────────────────────────────────────────────────────────────

    @Test
    void anAnswerWithoutUsageHasNoUsageAndAnUnknownFinishReasonIsUnknown() {
        server.enqueue(
                ok(
                        """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"something_new"}]}"""));
        LLMResponse r = provider(model()).chat(ask("hi"));
        assertThat(r.getContent()).isEqualTo("ok");
        assertThat(r.getTokenUsage()).isNull();
        assertThat(r.getFinishReason()).isEqualTo(FinishReason.UNKNOWN);
        assertThat(r.getMetadata()).containsEntry("finish_reason_raw", "something_new");
    }

    @Test
    void contentFilterIsReadAsContentFilterNotAnError() {
        server.enqueue(ok(completion("content_filter", "", 5, 0)));
        assertThat(provider(model()).chat(ask("hi")).getFinishReason())
                .isEqualTo(FinishReason.CONTENT_FILTER);
    }

    @Test
    void emptyChoicesAndAnErrorInsideA200AreProviderErrors() {
        server.enqueue(ok("{\"choices\":[]}"));
        assertThatThrownBy(() -> provider(model()).chat(ask("hi")))
                .isInstanceOf(ProviderException.class);
        server.enqueue(ok("{\"error\":{\"message\":\"model overloaded\"}}"));
        assertThatThrownBy(() -> provider(model()).chat(ask("hi")))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("model overloaded");
        server.enqueue(ok("not json at all"));
        assertThatThrownBy(() -> provider(model()).chat(ask("hi")))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void reasoningTextIsNeverPartOfTheAnswer() {
        server.enqueue(
                ok(
                        """
                {"choices":[{"index":0,"message":{"role":"assistant","reasoning_content":"Let me think about Paris.","content":"Paris."},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":5,"completion_tokens":9,"total_tokens":14,"completion_tokens_details":{"reasoning_tokens":6}}}"""));
        LLMResponse r = provider(model()).chat(ask("Capital of France?"));
        assertThat(r.getContent()).isEqualTo("Paris.");
        assertThat(r.getMetadata()).containsEntry("usage.reasoning_tokens", 6);
    }

    @Test
    void aRefusalIsContentBlockedAndNamesTheProvider() {
        server.enqueue(ok(refusal("policy")));
        assertThatThrownBy(() -> provider(model()).chat(ask("…")))
                .isInstanceOf(ContentBlockedException.class)
                .hasMessageContaining("can't help");
    }

    // ── tools ───────────────────────────────────────────────────────────────────────────────

    @Test
    void toolsAreOfferedAsFunctionsAndNoneIsHonoured() throws Exception {
        LLMProvider p = provider(model());
        assertThat(p.supportsToolCalling()).isEqualTo(dialect().toolCalling());
        if (!dialect().toolCalling()) return;
        server.enqueue(ok(answer("hi", 1, 1)));
        p.chat(LLMRequest.builder().addUserMessage("weather?").tools(List.of(WEATHER)).build());
        JsonNode sent = sent();
        JsonNode tool = sent.path("tools").path(0);
        assertThat(tool.path("type").asText()).isEqualTo("function");
        assertThat(tool.path("function").path("name").asText()).isEqualTo("get_weather");
        assertThat(tool.path("function").path("parameters").path("properties").has("city"))
                .isTrue();

        server.enqueue(ok(answer("hi", 1, 1)));
        p.chat(
                LLMRequest.builder()
                        .addUserMessage("weather?")
                        .tools(List.of(WEATHER))
                        .toolChoice(LLMRequest.ToolChoice.NONE)
                        .build());
        assertThat(sent().path("tool_choice").asText()).isEqualTo("none");
    }

    @Test
    void aToolCallIsParsedAndTheNullContentIsAnEmptyAnswer() {
        if (!dialect().toolCalling()) return;
        server.enqueue(ok(toolCallBody(null)));
        LLMResponse r =
                provider(model())
                        .chat(
                                LLMRequest.builder()
                                        .addUserMessage("weather?")
                                        .tools(List.of(WEATHER))
                                        .build());
        assertThat(r.getFinishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(r.getContent()).isEmpty();
        assertThat(r.getToolCalls()).hasSize(1);
        ToolCall call = r.getToolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_1");
        assertThat(call.name()).isEqualTo("get_weather");
        assertThat(call.arguments()).containsEntry("city", "Pune");
    }

    @Test
    void aToolTurnIsReplayedAsToolCallsAndAToolMessage() throws Exception {
        if (!dialect().toolCalling()) return;
        server.enqueue(ok(answer("It is sunny.", 30, 5)));
        ToolCall call = new ToolCall("call_1", "get_weather", Map.of("city", "Pune"));
        provider(model())
                .chat(
                        LLMRequest.builder()
                                .addUserMessage("weather in Pune?")
                                .addMessage(Message.assistantToolCalls("", List.of(call), Map.of()))
                                .addMessage(
                                        Message.toolResult(
                                                "call_1", "get_weather", "{\"temp\":31}"))
                                .tools(List.of(WEATHER))
                                .build());
        JsonNode messages = sent().path("messages");
        JsonNode assistant = messages.path(1);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        assertThat(assistant.path("tool_calls").path(0).path("id").asText()).isEqualTo("call_1");
        assertThat(assistant.path("tool_calls").path(0).path("function").path("arguments").asText())
                .isEqualTo("{\"city\":\"Pune\"}");
        JsonNode tool = messages.path(2);
        assertThat(tool.path("role").asText()).isEqualTo("tool");
        assertThat(tool.path("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(tool.path("content").asText()).isEqualTo("{\"temp\":31}");
    }

    @Test
    void toolArgumentsThatAreNotJsonAreAProviderError() {
        if (!dialect().toolCalling()) return;
        server.enqueue(
                ok(toolCallBody(null).replace("{\\\"city\\\":\\\"Pune\\\"}", "{\\\"city\\\": ")));
        assertThatThrownBy(
                        () ->
                                provider(model())
                                        .chat(
                                                LLMRequest.builder()
                                                        .addUserMessage("w")
                                                        .tools(List.of(WEATHER))
                                                        .build()))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("get_weather");
    }

    @Test
    void reasoningThatAccompaniesAToolCallComesBackWithTheToolTurn() throws Exception {
        if (!dialect().toolCalling() || !dialect().reasoningFields().contains("reasoning_content"))
            return;
        server.enqueue(ok(toolCallBody("I should look the weather up.")));
        LLMProvider p = provider(model());
        LLMResponse first =
                p.chat(
                        LLMRequest.builder()
                                .addUserMessage("weather?")
                                .tools(List.of(WEATHER))
                                .build());
        assertThat(first.getProviderData()).isNotEmpty();

        server.enqueue(ok(answer("Sunny.", 30, 3)));
        p.chat(
                LLMRequest.builder()
                        .addUserMessage("weather?")
                        .addMessage(
                                Message.assistantToolCalls(
                                        "", first.getToolCalls(), first.getProviderData()))
                        .addMessage(Message.toolResult("call_1", "get_weather", "{}"))
                        .tools(List.of(WEATHER))
                        .build());
        server.takeRequest();
        assertThat(sent().path("messages").path(1).path("reasoning_content").asText())
                .isEqualTo("I should look the weather up.");
    }

    // ── streaming ───────────────────────────────────────────────────────────────────────────

    @Test
    void streamsAskForUsageAndSurviveAMissingDone() throws Exception {
        String body =
                stream(List.of("a", "b"), 3, 2).getBody().readUtf8().replace(data("[DONE]"), "");
        server.enqueue(sse(body));
        List<LLMResponse> chunks;
        try (Stream<LLMResponse> s = provider(model()).chatStream(ask("x"))) {
            chunks = s.toList();
        }
        JsonNode sent = sent();
        assertThat(sent.path("stream").asBoolean()).isTrue();
        if (dialect().streamUsage())
            assertThat(sent.path("stream_options").path("include_usage").asBoolean()).isTrue();
        LLMResponse last = chunks.get(chunks.size() - 1);
        assertThat(last.getFinishReason()).isEqualTo(FinishReason.STOP);
        assertThat(last.getTokenUsage().getTotalTokens()).isEqualTo(5);
    }

    @Test
    void streamedToolCallsArriveAsDeltasAndTheFinalChunkSaysToolCalls() {
        if (!dialect().toolCalling()) return;
        String body =
                data(
                                "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_9\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}")
                        + data(
                                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\"}}]},\"finish_reason\":null}]}")
                        + data(
                                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Pune\\\"}\"}}]},\"finish_reason\":null}]}")
                        + data(
                                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4,\"total_tokens\":13}}")
                        + data("[DONE]");
        server.enqueue(sse(body));
        List<LLMResponse> chunks;
        try (Stream<LLMResponse> s =
                provider(model())
                        .chatStream(
                                LLMRequest.builder()
                                        .addUserMessage("w")
                                        .tools(List.of(WEATHER))
                                        .build())) {
            chunks = s.toList();
        }
        List<ToolCallDelta> deltas =
                chunks.stream().map(LLMResponse::getToolCallDelta).filter(d -> d != null).toList();
        assertThat(deltas).hasSize(3);
        assertThat(deltas.get(0)).isEqualTo(new ToolCallDelta(0, "call_9", "get_weather", ""));
        assertThat(deltas.stream().map(ToolCallDelta::argumentsFragment).reduce("", String::concat))
                .isEqualTo("{\"city\":\"Pune\"}");
        LLMResponse last = chunks.get(chunks.size() - 1);
        assertThat(last.getFinishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(last.getTokenUsage().getTotalTokens()).isEqualTo(13);
    }

    @Test
    void aStreamThatEndsInsideAToolCallIsAProviderError() {
        if (!dialect().toolCalling()) return;
        String body =
                data(
                        "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_9\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"ci\"}}]},\"finish_reason\":null}]}");
        server.enqueue(sse(body));
        assertThatThrownBy(
                        () ->
                                provider(model())
                                        .chatStream(
                                                LLMRequest.builder()
                                                        .addUserMessage("w")
                                                        .tools(List.of(WEATHER))
                                                        .build())
                                        .toList())
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("tool call");
    }

    @Test
    void streamedReasoningIsShownOnlyWhenAskedForAndNeverInTheText() {
        String body =
                data(
                                "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"thinking \"},\"finish_reason\":null}]}")
                        + data(
                                "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"hard\"},\"finish_reason\":null}]}")
                        + data(delta("Answer."))
                        + data(
                                "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}")
                        + data("[DONE]");
        server.enqueue(sse(body));
        List<LLMResponse> plain = provider(model()).chatStream(ask("x")).toList();
        assertThat(plain).extracting(LLMResponse::getReasoning).containsOnlyNulls();
        assertThat(plain.stream().map(LLMResponse::getContent).reduce("", String::concat))
                .isEqualTo("Answer.");

        server.enqueue(sse(body));
        List<LLMResponse> shown =
                provider(model())
                        .chatStream(
                                LLMRequest.builder().addUserMessage("x").reasoning(true).build())
                        .toList();
        assertThat(
                        shown.stream()
                                .map(LLMResponse::getReasoning)
                                .filter(r -> r != null)
                                .reduce("", String::concat))
                .isEqualTo("thinking hard");
        assertThat(shown.stream().map(LLMResponse::getContent).reduce("", String::concat))
                .isEqualTo("Answer.");
    }
}
