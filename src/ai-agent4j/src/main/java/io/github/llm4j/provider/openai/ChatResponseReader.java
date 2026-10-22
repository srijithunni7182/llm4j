package io.github.llm4j.provider.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.exception.ContentBlockedException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.provider.Providers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a chat-completions response (whole, or one streamed chunk's pieces) for a {@link Dialect}.
 */
final class ChatResponseReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ChatResponseReader() {}

    static LLMResponse read(Dialect dialect, String body, String requestedModel) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new ProviderException(dialect.name(), "Response is not JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new ProviderException(dialect.name(), "Response is not a JSON object");
        }
        failOnError(dialect, root);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ProviderException(dialect.name(), "No choices in response");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        if (message.hasNonNull("refusal") && !message.path("refusal").asText().isBlank()) {
            throw new ContentBlockedException(
                    dialect.name(), "refused: " + message.path("refusal").asText());
        }
        String content = message.path("content").asText("");
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            calls.add(
                    new ToolCall(
                            call.path("id").asText(null),
                            function.path("name").asText(),
                            arguments(
                                    dialect,
                                    function.path("name").asText(),
                                    function.path("arguments").asText("{}"))));
        }
        String reasoning = reasoning(dialect, message);
        String raw =
                choice.hasNonNull("finish_reason") ? choice.get("finish_reason").asText() : null;
        LLMResponse.Builder b =
                LLMResponse.builder()
                        .content(content)
                        .model(
                                root.hasNonNull("model")
                                        ? root.get("model").asText()
                                        : requestedModel)
                        .toolCalls(calls)
                        .finishReason(calls.isEmpty() ? raw : "tool_calls")
                        .addMetadata(Providers.FINISH_REASON_RAW, raw);
        if (root.hasNonNull("id")) b.addMetadata("id", root.get("id").asText());
        if (!calls.isEmpty() && reasoning != null && !reasoning.isEmpty()) {
            b.providerData(Map.of(ChatRequestWriter.REASONING, reasoning));
        }
        usage(root.path("usage"), b);
        return b.build();
    }

    /** An {@code error} object inside a 200 body (or a stream event) is a provider failure. */
    static void failOnError(Dialect dialect, JsonNode root) {
        if (!root.has("error") || root.get("error").isNull()) return;
        JsonNode error = root.get("error");
        String message =
                error.isTextual() ? error.asText() : error.path("message").asText(error.toString());
        throw new ProviderException(dialect.name(), message);
    }

    static Map<String, Object> arguments(Dialect dialect, String tool, String json) {
        try {
            JsonNode node = JSON.readTree(json == null || json.isBlank() ? "{}" : json);
            if (!node.isObject()) throw new IOException("not an object");
            return JSON.convertValue(node, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new ProviderException(
                    dialect.name(),
                    "The arguments of tool call " + tool + " are not a JSON object",
                    e);
        }
    }

    /** The first reasoning field the dialect knows that the message carries. */
    static String reasoning(Dialect dialect, JsonNode holder) {
        for (String field : dialect.reasoningFields()) {
            if (holder.hasNonNull(field) && !holder.get(field).asText().isEmpty()) {
                return holder.get(field).asText();
            }
        }
        return null;
    }

    /** Token counts, and cached and reasoning tokens when the vendor reports them. */
    static void usage(JsonNode usage, LLMResponse.Builder b) {
        if (!usage.isObject()) return;
        int in = usage.path("prompt_tokens").asInt(0);
        int out = usage.path("completion_tokens").asInt(0);
        b.tokenUsage(in, out, usage.path("total_tokens").asInt(in + out));
        JsonNode cached = usage.path("prompt_tokens_details").path("cached_tokens");
        if (cached.isNumber()) b.addMetadata("usage.cached_tokens", cached.asInt());
        else if (usage.path("cached_tokens").isNumber())
            b.addMetadata("usage.cached_tokens", usage.get("cached_tokens").asInt());
        JsonNode reasoning = usage.path("completion_tokens_details").path("reasoning_tokens");
        if (reasoning.isNumber()) b.addMetadata("usage.reasoning_tokens", reasoning.asInt());
    }
}
