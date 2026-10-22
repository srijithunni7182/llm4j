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
import io.github.llm4j.provider.openai.OpenAiResponsesProvider;
import io.github.llm4j.provider.openai.ResponsesDialect;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

/**
 * The provider contract for every Responses-schema dialect (OpenAI's Responses API, Perplexity's
 * Agent API): {@code instructions} and an {@code input} list instead of messages, {@code output[]}
 * items instead of choices, typed server-sent events (spec 08 §3.4).
 */
abstract class ResponsesContract extends ProviderContract {

    protected abstract ResponsesDialect dialect();

    @Override
    protected LLMProvider provider(String model) {
        return new OpenAiResponsesProvider(dialect(), config(url("/v1"), model));
    }

    @Override
    protected boolean supportsStopSequences() {
        return false;
    }

    @Override
    protected String nativeSystem(JsonNode request) {
        return text(request.path("instructions"));
    }

    @Override
    protected List<String> turns(JsonNode request) {
        List<String> out = new ArrayList<>();
        request.path("input")
                .forEach(m -> out.add(m.path("role").asText() + ": " + m.path("content").asText()));
        return out;
    }

    @Override
    protected Settings settings(JsonNode r) {
        return new Settings(
                integer(r.path("max_output_tokens")),
                decimal(r.path("temperature")),
                decimal(r.path("top_p")),
                null);
    }

    // ── wire formats ────────────────────────────────────────────────────────────────────────

    static String quote(String s) {
        return AnthropicContractTest.quote(s);
    }

    protected String response(
            String status, String incompleteReason, String outputJson, int in, int out) {
        return """
                {"id":"resp_1","object":"response","created_at":1760000000,"status":"%s","model":"%s",
                 %s"output":%s,
                 "usage":{"input_tokens":%d,"output_tokens":%d,"total_tokens":%d}}"""
                .formatted(
                        status,
                        model(),
                        incompleteReason == null
                                ? ""
                                : "\"incomplete_details\":{\"reason\":\""
                                        + incompleteReason
                                        + "\"},",
                        outputJson,
                        in,
                        out,
                        in + out);
    }

    static String message(String text) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":"
                + quote(text)
                + ",\"annotations\":[]}]}";
    }

    @Override
    protected String answer(String text, int in, int out) {
        return response("completed", null, "[" + message(text) + "]", in, out);
    }

    @Override
    protected String truncated(String text) {
        return response("incomplete", "max_output_tokens", "[" + message(text) + "]", 10, 5);
    }

    @Override
    protected String refusal(String category) {
        return response(
                "completed",
                null,
                "[{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\","
                        + "\"content\":[{\"type\":\"refusal\",\"refusal\":\"I can't help with that ("
                        + category
                        + ").\"}]}]",
                5,
                9);
    }

    @Override
    protected String overflowMessage() {
        return "Your input exceeds the context window of this model. Please adjust your input and try again.";
    }

    @Override
    protected String error(int status, String message) {
        return "{\"error\":{\"message\":"
                + quote(message)
                + ",\"type\":\"error_"
                + status
                + "\",\"code\":null}}";
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

    static String event(String type, String json) {
        return "event: " + type + "\ndata: " + json + "\n\n";
    }

    static String textDelta(String text) {
        return event(
                "response.output_text.delta",
                "{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"delta\":"
                        + quote(text)
                        + "}");
    }

    protected String completedEvent(String responseJson) {
        return event(
                "response.completed",
                "{\"type\":\"response.completed\",\"response\":" + compact(responseJson) + "}");
    }

    /** SSE data is one line: the JSON is re-written without its line breaks. */
    static String compact(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected MockResponse stream(List<String> pieces, int in, int out) {
        StringBuilder sb =
                new StringBuilder(
                        event(
                                "response.created",
                                "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"status\":\"in_progress\"}}"));
        sb.append(
                event(
                        "response.output_item.added",
                        "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}}"));
        pieces.forEach(p -> sb.append(textDelta(p)));
        sb.append(completedEvent(answer(String.join("", pieces), in, out)));
        return sse(sb.toString());
    }

    @Override
    protected MockResponse streamThenError(List<String> pieces) {
        StringBuilder sb = new StringBuilder();
        pieces.forEach(p -> sb.append(textDelta(p)));
        sb.append(
                event(
                        "error",
                        "{\"type\":\"error\",\"code\":\"server_error\",\"message\":\"internal error\"}"));
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

    private static final String CALL_ITEM =
            "{\"id\":\"fc_1\",\"type\":\"function_call\",\"status\":\"completed\",\"call_id\":\"call_1\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Pune\\\"}\"}";
    private static final String REASONING_ITEM =
            "{\"id\":\"rs_1\",\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"I should look it up.\"}]}";

    // ── where it talks ──────────────────────────────────────────────────────────────────────

    @Test
    void postsToTheResponsesPathWithABearerKey() throws Exception {
        server.enqueue(ok(answer("hi", 1, 1)));
        provider(model()).chat(ask("hi"));
        var r = server.takeRequest();
        assertThat(r.getPath()).isEqualTo("/v1" + dialect().path());
        assertThat(r.getHeader("Authorization")).isEqualTo("Bearer " + SECRET);
        assertThat(json.readTree(r.getBody().readUtf8()).path("model").asText()).isEqualTo(model());
    }

    @Test
    void stopSequencesAreLeftOutBecauseTheSchemaHasNone() throws Exception {
        server.enqueue(ok(answer("hi", 1, 1)));
        provider(model())
                .chat(
                        LLMRequest.builder()
                                .addUserMessage("hi")
                                .stopSequences(List.of("END"))
                                .build());
        assertThat(sent().has("stop")).isFalse();
    }

    @Test
    void theResponseIdIsReportedAndPreviousResponseIdContinuesTheConversation() throws Exception {
        server.enqueue(ok(answer("first", 1, 1)));
        LLMResponse first = provider(model()).chat(ask("hello"));
        assertThat(first.getMetadata()).containsEntry("response_id", "resp_1");
        sent();

        server.enqueue(ok(answer("second", 1, 1)));
        provider(model())
                .chat(
                        LLMRequest.builder()
                                .addUserMessage("hello")
                                .addAssistantMessage("first")
                                .addUserMessage("and then?")
                                .addParameter("previous_response_id", "resp_1")
                                .build());
        JsonNode sent = sent();
        assertThat(sent.path("previous_response_id").asText()).isEqualTo("resp_1");
        assertThat(turns(sent)).containsExactly("user: and then?");
    }

    // ── answers and odd answers ─────────────────────────────────────────────────────────────

    @Test
    void severalMessagesAreJoinedAndNonMessageItemsAreIgnored() {
        server.enqueue(
                ok(
                        response(
                                "completed",
                                null,
                                "["
                                        + REASONING_ITEM
                                        + ","
                                        + message("One. ")
                                        + ","
                                        + message("Two.")
                                        + "]",
                                4,
                                4)));
        LLMResponse r = provider(model()).chat(ask("count"));
        assertThat(r.getContent()).isEqualTo("One. Two.");
    }

    @Test
    void aMissingUsageAndAnUnknownStatusAreNotErrors() {
        server.enqueue(
                ok("{\"id\":\"r\",\"status\":\"mystery\",\"output\":[" + message("ok") + "]}"));
        LLMResponse r = provider(model()).chat(ask("hi"));
        assertThat(r.getContent()).isEqualTo("ok");
        assertThat(r.getTokenUsage()).isNull();
        assertThat(r.getFinishReason()).isEqualTo(FinishReason.UNKNOWN);
    }

    @Test
    void aFailedResponseAndAnErrorInsideA200AreProviderErrors() {
        server.enqueue(
                ok(
                        "{\"id\":\"r\",\"status\":\"failed\",\"error\":{\"message\":\"model overloaded\"},\"output\":[]}"));
        assertThatThrownBy(() -> provider(model()).chat(ask("hi")))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("model overloaded");
        server.enqueue(ok("{\"error\":{\"message\":\"bad things\"}}"));
        assertThatThrownBy(() -> provider(model()).chat(ask("hi")))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("bad things");
        server.enqueue(ok("not json"));
        assertThatThrownBy(() -> provider(model()).chat(ask("hi")))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void aContentFilteredResponseIsReadAsContentFilter() {
        server.enqueue(ok(response("incomplete", "content_filter", "[" + message("") + "]", 3, 0)));
        assertThat(provider(model()).chat(ask("hi")).getFinishReason())
                .isEqualTo(FinishReason.CONTENT_FILTER);
    }

    @Test
    void aRefusalNamesTheProvider() {
        server.enqueue(ok(refusal("policy")));
        assertThatThrownBy(() -> provider(model()).chat(ask("…")))
                .isInstanceOf(ContentBlockedException.class)
                .hasMessageContaining("can't help");
    }

    @Test
    void cachedAndReasoningTokensAreReportedInMetadata() {
        server.enqueue(
                ok(
                        """
                {"id":"r","status":"completed","output":[%s],
                 "usage":{"input_tokens":50,"output_tokens":30,"total_tokens":80,
                  "input_tokens_details":{"cached_tokens":40},"output_tokens_details":{"reasoning_tokens":12}}}"""
                                .formatted(message("ok"))));
        LLMResponse r = provider(model()).chat(ask("hi"));
        assertThat(r.getMetadata())
                .containsEntry("usage.cached_tokens", 40)
                .containsEntry("usage.reasoning_tokens", 12);
    }

    // ── tools ───────────────────────────────────────────────────────────────────────────────

    @Test
    void toolsAreOfferedAsFlatFunctions() throws Exception {
        server.enqueue(ok(answer("hi", 1, 1)));
        LLMProvider p = provider(model());
        assertThat(p.supportsToolCalling()).isTrue();
        p.chat(LLMRequest.builder().addUserMessage("weather?").tools(List.of(WEATHER)).build());
        JsonNode tool = sent().path("tools").path(0);
        assertThat(tool.path("type").asText()).isEqualTo("function");
        assertThat(tool.path("name").asText()).isEqualTo("get_weather");
        assertThat(tool.path("parameters").path("properties").has("city")).isTrue();

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
    void aFunctionCallIsAToolCallAndTheWholeTurnComesBackWithTheToolResult() throws Exception {
        server.enqueue(
                ok(
                        response(
                                "completed",
                                null,
                                "[" + REASONING_ITEM + "," + CALL_ITEM + "]",
                                20,
                                8)));
        LLMProvider p = provider(model());
        LLMResponse first =
                p.chat(
                        LLMRequest.builder()
                                .addUserMessage("weather in Pune?")
                                .tools(List.of(WEATHER))
                                .build());
        sent();
        assertThat(first.getFinishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(first.getContent()).isEmpty();
        ToolCall call = first.getToolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_1");
        assertThat(call.name()).isEqualTo("get_weather");
        assertThat(call.arguments()).containsEntry("city", "Pune");
        assertThat(first.getProviderData()).isNotEmpty();

        server.enqueue(ok(answer("Sunny.", 30, 3)));
        p.chat(
                LLMRequest.builder()
                        .addUserMessage("weather in Pune?")
                        .addMessage(
                                Message.assistantToolCalls(
                                        "", first.getToolCalls(), first.getProviderData()))
                        .addMessage(Message.toolResult("call_1", "get_weather", "{\"temp\":31}"))
                        .tools(List.of(WEATHER))
                        .build());
        JsonNode input = sent().path("input");
        List<String> types = new ArrayList<>();
        input.forEach(i -> types.add(i.path("type").asText("message")));
        boolean replaysReasoning = dialect().replayTypes().contains("reasoning");
        assertThat(types)
                .containsExactlyElementsOf(
                        replaysReasoning
                                ? List.of(
                                        "message",
                                        "reasoning",
                                        "function_call",
                                        "function_call_output")
                                : List.of("message", "function_call", "function_call_output"));
        int callAt = replaysReasoning ? 2 : 1;
        assertThat(input.path(callAt).path("call_id").asText()).isEqualTo("call_1");
        assertThat(input.path(callAt + 1).path("call_id").asText()).isEqualTo("call_1");
        assertThat(input.path(callAt + 1).path("output").asText()).isEqualTo("{\"temp\":31}");
    }

    @Test
    void aToolTurnWithoutProviderDataIsRebuiltFromTheToolCalls() throws Exception {
        server.enqueue(ok(answer("Sunny.", 30, 3)));
        provider(model())
                .chat(
                        LLMRequest.builder()
                                .addUserMessage("weather?")
                                .addMessage(
                                        Message.assistantToolCalls(
                                                "",
                                                List.of(
                                                        new ToolCall(
                                                                "call_7",
                                                                "get_weather",
                                                                Map.of("city", "Pune"))),
                                                Map.of()))
                                .addMessage(Message.toolResult("call_7", "get_weather", "{}"))
                                .tools(List.of(WEATHER))
                                .build());
        JsonNode input = sent().path("input");
        assertThat(input.path(1).path("type").asText()).isEqualTo("function_call");
        assertThat(input.path(1).path("arguments").asText()).isEqualTo("{\"city\":\"Pune\"}");
    }

    @Test
    void functionArgumentsThatAreNotJsonAreAProviderError() {
        server.enqueue(
                ok(
                        response(
                                "completed",
                                null,
                                "["
                                        + CALL_ITEM.replace(
                                                "{\\\"city\\\":\\\"Pune\\\"}", "{\\\"city\\\": ")
                                        + "]",
                                2,
                                2)));
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

    // ── streaming ───────────────────────────────────────────────────────────────────────────

    @Test
    void aStreamCutBeforeItCompletesIsAProviderError() {
        server.enqueue(sse(textDelta("par")));
        assertThatThrownBy(() -> provider(model()).chatStream(ask("x")).toList())
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("complet");
    }

    @Test
    void streamedFunctionCallsArriveAsDeltasAndTheFinalChunkCarriesTheTurn() {
        String done = response("completed", null, "[" + CALL_ITEM + "]", 9, 4);
        server.enqueue(
                sse(
                        event(
                                        "response.output_item.added",
                                        "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"get_weather\",\"arguments\":\"\"}}")
                                + event(
                                        "response.function_call_arguments.delta",
                                        "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"output_index\":0,\"delta\":\"{\\\"city\\\":\"}")
                                + event(
                                        "response.function_call_arguments.delta",
                                        "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"output_index\":0,\"delta\":\"\\\"Pune\\\"}\"}")
                                + completedEvent(done)));
        List<LLMResponse> chunks =
                provider(model())
                        .chatStream(
                                LLMRequest.builder()
                                        .addUserMessage("w")
                                        .tools(List.of(WEATHER))
                                        .build())
                        .toList();
        List<ToolCallDelta> deltas =
                chunks.stream().map(LLMResponse::getToolCallDelta).filter(d -> d != null).toList();
        assertThat(deltas.get(0)).isEqualTo(new ToolCallDelta(0, "call_1", "get_weather", ""));
        assertThat(deltas.stream().map(ToolCallDelta::argumentsFragment).reduce("", String::concat))
                .isEqualTo("{\"city\":\"Pune\"}");
        LLMResponse last = chunks.get(chunks.size() - 1);
        assertThat(last.getFinishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(last.getTokenUsage().getTotalTokens()).isEqualTo(13);
        assertThat(last.getProviderData()).isNotEmpty();
    }

    @Test
    void streamedReasoningSummariesAreShownOnlyWhenAskedForAndNeverInTheText() {
        String body =
                event(
                                "response.reasoning_summary_text.delta",
                                "{\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"rs_1\",\"output_index\":0,\"summary_index\":0,\"delta\":\"thinking hard\"}")
                        + textDelta("Answer.")
                        + completedEvent(answer("Answer.", 1, 2));
        server.enqueue(sse(body));
        List<LLMResponse> plain = provider(model()).chatStream(ask("x")).toList();
        assertThat(plain).extracting(LLMResponse::getReasoning).containsOnlyNulls();
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

    @Test
    void aFailedResponseEventEndsTheStreamWithAProviderError() {
        server.enqueue(
                sse(
                        textDelta("par")
                                + event(
                                        "response.failed",
                                        "{\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"message\":\"boom\"}}}")));
        assertThatThrownBy(() -> provider(model()).chatStream(ask("x")).toList())
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void anIncompleteStreamReadsAsLength() {
        server.enqueue(
                sse(
                        textDelta("Once")
                                + event(
                                        "response.incomplete",
                                        "{\"type\":\"response.incomplete\",\"response\":"
                                                + compact(truncated("Once"))
                                                + "}")));
        List<LLMResponse> chunks = provider(model()).chatStream(ask("x")).toList();
        assertThat(chunks.get(chunks.size() - 1).getFinishReason()).isEqualTo(FinishReason.LENGTH);
    }
}
