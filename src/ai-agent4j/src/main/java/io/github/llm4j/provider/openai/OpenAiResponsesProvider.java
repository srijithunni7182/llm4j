package io.github.llm4j.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.grounding.GroundingOption;
import io.github.llm4j.grounding.GroundingSupport;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.http.StreamingBody;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCallDelta;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.Providers;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import okhttp3.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A provider for APIs that speak the OpenAI <b>Responses</b> schema: {@code instructions} and an
 * {@code input} list in, {@code output[]} items out, typed server-sent events when streaming. It
 * serves Perplexity's Agent API ({@code POST /v1/agent}, one key for OpenAI, Anthropic, Google and
 * xAI models), xAI's and OpenAI's own Responses APIs; what differs is data in a {@link
 * ResponsesDialect}. Where the dialect has a {@link ResponsesGrounding}, a request with {@link
 * LLMRequest#getGrounding()} gets the vendor's web-search tool and the answer carries typed
 * citations and search usage.
 *
 * <pre>{@code
 * LLMConfig cfg = LLMConfig.builder().apiKey(System.getenv("PERPLEXITY_API_KEY")).defaultModel("openai/gpt-5.6-sol").build();
 * LLMClient perplexity = new DefaultLLMClient(OpenAiResponsesProvider.perplexity(cfg));
 * }</pre>
 *
 * <p>Behaviour matches the other providers: system messages become {@code instructions}, tool calls
 * are {@code function_call} items (the turn's output items are kept in {@link
 * LLMResponse#getProviderData()} and sent back with the tool result, as reasoning models require),
 * a refusal throws {@link io.github.llm4j.exception.ContentBlockedException}, reasoning is never
 * part of the answer, and a stream ends with one final chunk. Stop sequences are not part of the
 * schema and are left out. Pass {@code previous_response_id} through {@code additionalParameters}
 * to continue a stored conversation (the response id is in the metadata under {@code response_id}).
 */
public class OpenAiResponsesProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiResponsesProvider.class);

    private final ResponsesDialect dialect;
    private final LLMConfig config;
    private final HttpClientWrapper http;
    private final String baseUrl;

    /**
     * The provider for {@code dialect}, calling the dialect's default base URL unless the
     * configuration names another.
     */
    public OpenAiResponsesProvider(ResponsesDialect dialect, LLMConfig config) {
        this(
                dialect,
                config,
                new HttpClientWrapper(Objects.requireNonNull(config, "config cannot be null")));
    }

    OpenAiResponsesProvider(ResponsesDialect dialect, LLMConfig config, HttpClientWrapper http) {
        this.dialect = Objects.requireNonNull(dialect, "dialect cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.http = http;
        String url = config.getBaseUrl() != null ? config.getBaseUrl() : dialect.defaultBaseUrl();
        if (url == null || url.isBlank()) {
            throw new InvalidRequestException(
                    dialect.name() + " has no default address: configure a base URL");
        }
        this.baseUrl = url.replaceAll("/+$", "");
        validate();
    }

    /** Perplexity's Agent API. */
    public static OpenAiResponsesProvider perplexity(LLMConfig config) {
        return new OpenAiResponsesProvider(ResponsesDialect.PERPLEXITY, config);
    }

    /** xAI's Responses API (the one with web search). */
    public static OpenAiResponsesProvider xai(LLMConfig config) {
        return new OpenAiResponsesProvider(ResponsesDialect.XAI, config);
    }

    /** OpenAI's own Responses API. */
    public static OpenAiResponsesProvider openai(LLMConfig config) {
        return new OpenAiResponsesProvider(ResponsesDialect.OPENAI, config);
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        String model = model(request);
        try {
            String body =
                    http.post(
                            url(),
                            ResponsesRequestWriter.write(dialect, request, model, false),
                            headers());
            return ResponsesReader.read(
                    dialect,
                    ResponsesReader.parse(dialect, body),
                    model,
                    request.getGrounding() != null);
        } catch (IOException | LLMException e) {
            throw Providers.typed(getProviderName(), "Failed to process chat request", e);
        }
    }

    /**
     * Streams typed events: {@code response.output_text.delta} is a chunk, reasoning summaries
     * become reasoning chunks when the request asked for them, a {@code function_call} item and its
     * argument deltas become {@link ToolCallDelta}s, and {@code response.completed} (or {@code
     * response.incomplete}) yields the final chunk with finish reason, usage and the turn's
     * provider data. A stream that ends before one of those arrives is a provider error.
     */
    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        String model = model(request);
        StreamingBody body;
        try {
            body =
                    http.stream(
                            url(),
                            ResponsesRequestWriter.write(dialect, request, model, true),
                            headers());
        } catch (IOException | LLMException e) {
            throw Providers.typed(getProviderName(), "Failed to start streaming", e);
        }
        String[] answering = {model};
        boolean[] finished = {false};
        boolean[] sawCall = {false};
        Map<String, Integer> callByItem = new HashMap<>();
        Map<Integer, Integer> callByOutput = new HashMap<>();
        Stream<LLMResponse> events =
                body.events()
                        .takeWhile(e -> !"[DONE]".equals(e.data().strip()))
                        .flatMap(
                                e -> {
                                    JsonNode data = ResponsesReader.parse(dialect, e.data());
                                    String type =
                                            data.path("type")
                                                    .asText(e.name() == null ? "" : e.name());
                                    switch (type) {
                                        case "response.output_text.delta" -> {
                                            String text = data.path("delta").asText("");
                                            return text.isEmpty()
                                                    ? Stream.empty()
                                                    : Stream.of(
                                                            Providers.textChunk(
                                                                    text, answering[0]));
                                        }
                                        case "response.reasoning_summary_text.delta" -> {
                                            String thought = data.path("delta").asText("");
                                            return !request.isReasoning() || thought.isEmpty()
                                                    ? Stream.empty()
                                                    : Stream.of(
                                                            Providers.reasoningChunk(
                                                                    thought, answering[0]));
                                        }
                                        case "response.output_item.added" -> {
                                            JsonNode item = data.path("item");
                                            if (!"function_call".equals(item.path("type").asText()))
                                                return Stream.empty();
                                            int index = callByItem.size();
                                            sawCall[0] = true;
                                            callByItem.put(item.path("id").asText(), index);
                                            callByOutput.put(
                                                    data.path("output_index").asInt(), index);
                                            return Stream.of(
                                                    Providers.toolCallChunk(
                                                            new ToolCallDelta(
                                                                    index,
                                                                    item.hasNonNull("call_id")
                                                                            ? item.get("call_id")
                                                                                    .asText()
                                                                            : item.path("id")
                                                                                    .asText(null),
                                                                    item.path("name").asText(null),
                                                                    item.path("arguments")
                                                                            .asText("")),
                                                            answering[0]));
                                        }
                                        case "response.function_call_arguments.delta" -> {
                                            Integer index =
                                                    callByItem.get(data.path("item_id").asText());
                                            if (index == null)
                                                index =
                                                        callByOutput.get(
                                                                data.path("output_index").asInt());
                                            if (index == null) return Stream.empty();
                                            return Stream.of(
                                                    Providers.toolCallChunk(
                                                            new ToolCallDelta(
                                                                    index,
                                                                    null,
                                                                    null,
                                                                    data.path("delta").asText("")),
                                                            answering[0]));
                                        }
                                        case "response.completed", "response.incomplete" -> {
                                            LLMResponse whole =
                                                    ResponsesReader.read(
                                                            dialect,
                                                            data.path("response"),
                                                            answering[0],
                                                            request.getGrounding() != null);
                                            finished[0] = true;
                                            return Stream.of(finalChunk(whole, sawCall[0]));
                                        }
                                        case "response.failed" -> throw new ProviderException(
                                                dialect.name(),
                                                data.path("response")
                                                        .path("error")
                                                        .path("message")
                                                        .asText(
                                                                data.path("error")
                                                                        .path("message")
                                                                        .asText(
                                                                                "The response failed")));
                                        case "error" -> throw new ProviderException(
                                                dialect.name(),
                                                data.path("message")
                                                        .asText(
                                                                data.path("error")
                                                                        .path("message")
                                                                        .asText("error")));
                                        default -> {
                                            return Stream.empty(); // created, in_progress, part and
                                            // search events, pings
                                        }
                                    }
                                });
        Stream<LLMResponse> check =
                Stream.of(0)
                        .flatMap(
                                x -> {
                                    if (!finished[0]) {
                                        throw new ProviderException(
                                                getProviderName(),
                                                "The stream ended before the response completed");
                                    }
                                    return Stream.<LLMResponse>empty();
                                });
        return Stream.concat(events, check).onClose(body::close);
    }

    /**
     * The last chunk: no text, the finish reason, usage and provider data of the whole response.
     */
    private static LLMResponse finalChunk(LLMResponse whole, boolean sawCall) {
        LLMResponse.FinishReason reason = whole.getFinishReason();
        if (sawCall
                && reason != LLMResponse.FinishReason.LENGTH
                && reason != LLMResponse.FinishReason.CONTENT_FILTER) {
            reason = LLMResponse.FinishReason.TOOL_CALLS;
        }
        LLMResponse.Builder b =
                LLMResponse.builder()
                        .content("")
                        .model(whole.getModel())
                        .finishReason(reason)
                        .providerData(whole.getProviderData())
                        .citations(whole.getCitations())
                        .searches(whole.getSearches())
                        .metadata(whole.getMetadata());
        if (whole.getTokenUsage() != null) b.tokenUsage(whole.getTokenUsage());
        return b.build();
    }

    @Override
    public String getProviderName() {
        return dialect.name();
    }

    @Override
    public boolean supportsToolCalling() {
        return true;
    }

    @Override
    public void validate() {
        if (dialect.keyRequired() && !config.hasApiKey()) {
            throw new AuthenticationException(
                    config.missingApiKeyMessage(
                            dialect.name()
                                    + " API key is required"
                                    + (dialect.keyEnv() == null
                                            ? ""
                                            : " (" + dialect.keyEnv() + ")")));
        }
    }

    @Override
    public GroundingSupport groundingSupport() {
        return dialect.grounding() == null ? GroundingSupport.NONE : GroundingSupport.NATIVE;
    }

    @Override
    public Set<GroundingOption> groundingOptions() {
        return dialect.grounding() == null ? Set.of() : dialect.grounding().options();
    }

    /** The dialect this provider speaks. */
    public ResponsesDialect dialect() {
        return dialect;
    }

    private String model(LLMRequest request) {
        String model = request.getModel() != null ? request.getModel() : config.getDefaultModel();
        if (model == null || model.isBlank())
            throw new InvalidRequestException("Model must be specified in request or config");
        return model;
    }

    private String url() {
        logger.debug("Calling {} at {}", dialect.name(), baseUrl);
        return baseUrl + dialect.path();
    }

    private Headers headers() {
        Headers.Builder h = new Headers.Builder().add("Content-Type", "application/json");
        if (config.hasApiKey())
            h.add("Authorization", "Bearer " + config.requireApiKey(dialect.name(), baseUrl));
        return h.build();
    }
}
