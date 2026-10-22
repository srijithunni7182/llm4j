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
 * Reads a Responses-schema response object (whole, or the final one of a stream) for a {@link
 * ResponsesDialect}.
 */
final class ResponsesReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ResponsesReader() {}

    static JsonNode parse(ResponsesDialect dialect, String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject())
                throw new ProviderException(dialect.name(), "Response is not a JSON object");
            return root;
        } catch (IOException e) {
            throw new ProviderException(dialect.name(), "Response is not JSON", e);
        }
    }

    static LLMResponse read(ResponsesDialect dialect, JsonNode root, String requestedModel) {
        failOnError(dialect, root);
        String status = root.path("status").asText(null);
        if ("failed".equals(status)) {
            throw new ProviderException(
                    dialect.name(),
                    root.path("error").path("message").asText("The response failed"));
        }
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode item : root.path("output")) {
            switch (item.path("type").asText()) {
                case "message" -> {
                    for (JsonNode part : item.path("content")) {
                        if ("output_text".equals(part.path("type").asText())) {
                            text.append(part.path("text").asText(""));
                        } else if ("refusal".equals(part.path("type").asText())) {
                            throw new ContentBlockedException(
                                    dialect.name(), "refused: " + part.path("refusal").asText());
                        }
                    }
                }
                case "function_call" -> {
                    String name = item.path("name").asText();
                    calls.add(
                            new ToolCall(
                                    item.hasNonNull("call_id")
                                            ? item.get("call_id").asText()
                                            : item.path("id").asText(null),
                                    name,
                                    arguments(dialect, name, item.path("arguments").asText("{}"))));
                }
                default -> {
                    // reasoning, search results and other items are not part of the answer
                }
            }
        }
        String reason = root.path("incomplete_details").path("reason").asText(null);
        String raw = "incomplete".equals(status) && reason != null ? reason : status;
        LLMResponse.Builder b =
                LLMResponse.builder()
                        .content(text.toString())
                        .model(
                                root.hasNonNull("model")
                                        ? root.get("model").asText()
                                        : requestedModel)
                        .toolCalls(calls)
                        .finishReason(finish(status, reason, !calls.isEmpty()))
                        .addMetadata(Providers.FINISH_REASON_RAW, raw);
        if (root.hasNonNull("id")) b.addMetadata("response_id", root.get("id").asText());
        if (!calls.isEmpty()) {
            b.providerData(
                    Map.of(
                            ResponsesRequestWriter.OUTPUT,
                            JSON.convertValue(
                                    root.path("output"),
                                    new TypeReference<List<Map<String, Object>>>() {})));
        }
        usage(root.path("usage"), b);
        return b.build();
    }

    /**
     * The normalised finish reason ({@code stop}, {@code length}, …), or null when the status is
     * not one we know.
     */
    static String finish(String status, String incompleteReason, boolean calls) {
        if (calls) return "tool_calls";
        if ("completed".equals(status)) return "stop";
        if ("incomplete".equals(status)) {
            if ("content_filter".equals(incompleteReason)) return "content_filter";
            return "length";
        }
        return null;
    }

    static void failOnError(ResponsesDialect dialect, JsonNode root) {
        JsonNode error = root.get("error");
        if (error == null || error.isNull()) return;
        String message =
                error.isTextual() ? error.asText() : error.path("message").asText(error.toString());
        throw new ProviderException(dialect.name(), message);
    }

    static Map<String, Object> arguments(ResponsesDialect dialect, String tool, String json) {
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

    static void usage(JsonNode usage, LLMResponse.Builder b) {
        if (!usage.isObject()) return;
        int in = usage.path("input_tokens").asInt(0);
        int out = usage.path("output_tokens").asInt(0);
        b.tokenUsage(in, out, usage.path("total_tokens").asInt(in + out));
        JsonNode cached = usage.path("input_tokens_details").path("cached_tokens");
        if (cached.isNumber()) b.addMetadata("usage.cached_tokens", cached.asInt());
        JsonNode reasoning = usage.path("output_tokens_details").path("reasoning_tokens");
        if (reasoning.isNumber()) b.addMetadata("usage.reasoning_tokens", reasoning.asInt());
    }
}
