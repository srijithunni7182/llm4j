package io.github.llm4j.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.http.HttpClientWrapper;
import io.github.llm4j.http.StreamingBody;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCallDelta;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.Providers;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Stream;
import okhttp3.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One provider for the whole OpenAI-style family: OpenAI, xAI, Kimi, Qwen (DashScope), the
 * Perplexity Orchestrator, and any server that speaks {@code POST …/chat/completions} (DeepSeek,
 * Groq, Together, vLLM, LM Studio). What differs between vendors is data in a {@link Dialect}, and
 * what differs between one vendor's models is in {@link ModelTraits}; this class names no vendor.
 *
 * <pre>{@code
 * LLMConfig cfg = LLMConfig.builder().apiKey(System.getenv("OPENAI_API_KEY")).defaultModel("gpt-5-mini").build();
 * LLMClient openai = new DefaultLLMClient(OpenAiCompatibleProvider.openai(cfg));
 * }</pre>
 *
 * <p>It behaves like the other providers: system messages stay in the message list, tools are
 * offered as functions, a refusal throws {@link io.github.llm4j.exception.ContentBlockedException},
 * reasoning text is never part of the answer, and streams end with one final chunk that carries the
 * finish reason and the token usage.
 */
public class OpenAiCompatibleProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Dialect dialect;
    private final LLMConfig config;
    private final HttpClientWrapper http;
    private final String baseUrl;

    /**
     * The provider for {@code dialect}, calling the dialect's default base URL unless the
     * configuration names another.
     */
    public OpenAiCompatibleProvider(Dialect dialect, LLMConfig config) {
        this(
                dialect,
                config,
                new HttpClientWrapper(Objects.requireNonNull(config, "config cannot be null")));
    }

    OpenAiCompatibleProvider(Dialect dialect, LLMConfig config, HttpClientWrapper http) {
        this.dialect = Objects.requireNonNull(dialect, "dialect cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.http = http;
        String url = config.getBaseUrl() != null ? config.getBaseUrl() : dialect.defaultBaseUrl();
        if (url == null || url.isBlank()) {
            throw new InvalidRequestException(
                    dialect.name()
                            + " has no default address: configure a base URL"
                            + (dialect.baseUrlEnv() == null
                                    ? ""
                                    : " (" + dialect.baseUrlEnv() + ")"));
        }
        this.baseUrl = url.replaceAll("/+$", "");
        validate();
    }

    /** OpenAI's chat completions. */
    public static OpenAiCompatibleProvider openai(LLMConfig config) {
        return new OpenAiCompatibleProvider(Dialect.OPENAI, config);
    }

    /** xAI (Grok). */
    public static OpenAiCompatibleProvider xai(LLMConfig config) {
        return new OpenAiCompatibleProvider(Dialect.XAI, config);
    }

    /** Moonshot's Kimi (preview). */
    public static OpenAiCompatibleProvider kimi(LLMConfig config) {
        return new OpenAiCompatibleProvider(Dialect.KIMI, config);
    }

    /** Alibaba's Qwen through DashScope's compatible mode (preview). */
    public static OpenAiCompatibleProvider qwen(LLMConfig config) {
        return new OpenAiCompatibleProvider(Dialect.QWEN, config);
    }

    /** Any provider with the given dialect. */
    public static OpenAiCompatibleProvider of(Dialect dialect, LLMConfig config) {
        return new OpenAiCompatibleProvider(dialect, config);
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        String model = model(request);
        try {
            String body =
                    http.post(
                            url(),
                            ChatRequestWriter.write(dialect, request, model, false),
                            headers());
            return ChatResponseReader.read(dialect, body, model);
        } catch (IOException | LLMException e) {
            throw Providers.typed(getProviderName(), "Failed to process chat request", e);
        }
    }

    /**
     * Streams with {@code "stream": true}: each {@code choices[0].delta.content} is a chunk,
     * reasoning text becomes reasoning chunks when the request asked for it, tool calls arrive as
     * {@link ToolCallDelta}s, and the finish reason and usage (which many vendors send in a later
     * chunk with no choices) go in the final chunk. A stream with no {@code [DONE]} still ends
     * cleanly.
     */
    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        String model = model(request);
        StreamingBody body;
        try {
            body =
                    http.stream(
                            url(),
                            ChatRequestWriter.write(dialect, request, model, true),
                            headers());
        } catch (IOException | LLMException e) {
            throw Providers.typed(getProviderName(), "Failed to start streaming", e);
        }
        String[] answering = {model};
        String[] finish = {null};
        Integer[] usage = {null, null};
        StringBuilder reasoning = new StringBuilder();
        TreeSet<Integer> openCalls = new TreeSet<>();
        Stream<LLMResponse> events =
                body.events()
                        .takeWhile(event -> !"[DONE]".equals(event.data().strip()))
                        .flatMap(
                                event -> {
                                    JsonNode root = parse(event.data());
                                    ChatResponseReader.failOnError(dialect, root);
                                    if (root.hasNonNull("model"))
                                        answering[0] = root.get("model").asText();
                                    JsonNode u = root.path("usage");
                                    if (u.isObject()) {
                                        usage[0] = u.path("prompt_tokens").asInt(0);
                                        usage[1] = u.path("completion_tokens").asInt(0);
                                    }
                                    JsonNode choice = root.path("choices").path(0);
                                    if (choice.hasNonNull("finish_reason")) {
                                        finish[0] = choice.get("finish_reason").asText();
                                        openCalls.clear();
                                    }
                                    JsonNode delta = choice.path("delta");
                                    Stream.Builder<LLMResponse> out = Stream.builder();
                                    String thought = ChatResponseReader.reasoning(dialect, delta);
                                    if (thought != null) {
                                        reasoning.append(thought);
                                        if (request.isReasoning())
                                            out.add(
                                                    Providers.reasoningChunk(
                                                            thought, answering[0]));
                                    }
                                    String text = delta.path("content").asText("");
                                    if (!text.isEmpty())
                                        out.add(Providers.textChunk(text, answering[0]));
                                    for (JsonNode call : delta.path("tool_calls")) {
                                        int index = call.path("index").asInt(0);
                                        JsonNode function = call.path("function");
                                        openCalls.add(index);
                                        out.add(
                                                Providers.toolCallChunk(
                                                        new ToolCallDelta(
                                                                index,
                                                                call.path("id").asText(null),
                                                                function.path("name").asText(null),
                                                                function.path("arguments")
                                                                        .asText("")),
                                                        answering[0]));
                                    }
                                    return out.build();
                                });
        Stream<LLMResponse> tail =
                Stream.of(0)
                        .map(
                                x -> {
                                    if (!openCalls.isEmpty()) {
                                        throw new ProviderException(
                                                getProviderName(),
                                                "The stream ended inside a tool call");
                                    }
                                    Map<String, Object> data =
                                            "tool_calls".equals(finish[0]) && reasoning.length() > 0
                                                    ? Map.of(
                                                            ChatRequestWriter.REASONING,
                                                            reasoning.toString())
                                                    : Map.of();
                                    return Providers.finalChunk(
                                            finish[0], usage[0], usage[1], answering[0], data);
                                });
        return Stream.concat(events, tail).onClose(body::close);
    }

    private JsonNode parse(String data) {
        try {
            return JSON.readTree(data);
        } catch (IOException e) {
            throw new ProviderException(getProviderName(), "Unreadable stream event: " + data, e);
        }
    }

    @Override
    public String getProviderName() {
        return dialect.name();
    }

    @Override
    public boolean supportsToolCalling() {
        return dialect.toolCalling();
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

    /** The dialect this provider speaks. */
    public Dialect dialect() {
        return dialect;
    }

    private String model(LLMRequest request) {
        String model = request.getModel() != null ? request.getModel() : config.getDefaultModel();
        if (model == null || model.isBlank()) model = dialect.fallbackModel();
        if (model == null || model.isBlank()) {
            throw new InvalidRequestException("Model must be specified in request or config");
        }
        return model;
    }

    private String url() {
        StringBuilder url = new StringBuilder(baseUrl).append(dialect.chatPath());
        String sep = "?";
        for (Map.Entry<String, String> q : dialect.queryParams().entrySet()) {
            url.append(sep).append(q.getKey()).append('=').append(q.getValue());
            sep = "&";
        }
        logger.debug("Calling {} at {}", dialect.name(), baseUrl);
        return url.toString();
    }

    private Headers headers() {
        Headers.Builder h = new Headers.Builder().add("Content-Type", "application/json");
        if (config.hasApiKey()) {
            h.add(
                    dialect.authHeader(),
                    dialect.authScheme() + config.requireApiKey(dialect.name(), baseUrl));
        }
        return h.build();
    }
}
