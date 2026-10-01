package io.github.llm4j.provider.contract;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.exception.ServiceUnavailableException;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;

/** The contract for Anthropic, with the Messages API's wire formats. */
class AnthropicContractTest extends ProviderContract {

    @Override
    protected LLMProvider provider(String model) {
        return new AnthropicProvider(config(url(""), model));
    }

    @Override
    protected String model() {
        return "claude-haiku-4-5"; // accepts sampling settings, so C3 sees them
    }

    @Override
    protected String nativeSystem(JsonNode request) {
        return text(request.path("system"));
    }

    @Override
    protected List<String> turns(JsonNode request) {
        List<String> out = new ArrayList<>();
        request.path("messages").forEach(m -> out.add(m.path("role").asText() + ": " + m.path("content").asText()));
        return out;
    }

    @Override
    protected Settings settings(JsonNode r) {
        return new Settings(integer(r.path("max_tokens")), decimal(r.path("temperature")), decimal(r.path("top_p")),
                strings(r.path("stop_sequences")));
    }

    static String message(String stopReason, String text, int in, int out) {
        return """
                {"id":"msg_01","type":"message","role":"assistant","model":"claude-haiku-4-5",
                 "content":[{"type":"thinking","thinking":"","signature":"sig"},{"type":"text","text":%s}],
                 "stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":%d,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":%d}}"""
                .formatted(quote(text), stopReason, in, out);
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Override
    protected String answer(String text, int in, int out) {
        return message("end_turn", text, in, out);
    }

    @Override
    protected String truncated(String text) {
        return message("max_tokens", text, 10, 5);
    }

    @Override
    protected String refusal(String category) {
        return """
                {"id":"msg_02","type":"message","role":"assistant","model":"claude-haiku-4-5","content":[],
                 "stop_reason":"refusal","stop_details":{"type":"refusal","category":"%s","explanation":"Declined."},
                 "usage":{"input_tokens":10,"output_tokens":0}}""".formatted(category);
    }

    @Override
    protected String error(int status, String message) {
        String type = switch (status) {
            case 401 -> "authentication_error";
            case 429 -> "rate_limit_error";
            case 529 -> "overloaded_error";
            case 500, 503 -> "api_error";
            default -> "invalid_request_error";
        };
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":" + quote(message) + "}}";
    }

    @Override
    protected MockResponse rateLimited(Instant reset) {
        return new MockResponse().setResponseCode(429)
                .setHeader("anthropic-ratelimit-requests-limit", "50")
                .setHeader("anthropic-ratelimit-requests-remaining", "0")
                .setHeader("anthropic-ratelimit-requests-reset", reset.toString())
                .setHeader("request-id", "req_123")
                .setBody(error(429, "Number of requests has exceeded your rate limit"));
    }

    static String event(String name, String data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
    }

    static String start(int in) {
        return event("message_start", """
                {"type":"message_start","message":{"id":"msg_s","type":"message","role":"assistant","model":"claude-haiku-4-5","content":[],"stop_reason":null,"usage":{"input_tokens":%d,"output_tokens":1}}}"""
                .formatted(in));
    }

    static String delta(String text) {
        return event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":" + quote(text) + "}}");
    }

    @Override
    protected MockResponse stream(List<String> pieces, int in, int out) {
        StringBuilder sb = new StringBuilder(start(in));
        sb.append(event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}"));
        sb.append(event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}"));
        sb.append(event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
        sb.append(event("ping", "{\"type\":\"ping\"}"));
        sb.append(event("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
        pieces.forEach(p -> sb.append(delta(p)));
        sb.append(event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}"));
        sb.append(event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":" + out + "}}"));
        sb.append(event("message_stop", "{\"type\":\"message_stop\"}"));
        return sse(sb.toString());
    }

    @Override
    protected MockResponse streamThenError(List<String> pieces) {
        StringBuilder sb = new StringBuilder(start(3));
        pieces.forEach(p -> sb.append(delta(p)));
        sb.append(event("error", "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"));
        return sse(sb.toString());
    }

    @Override
    protected Class<? extends RuntimeException> midStreamError() {
        return ServiceUnavailableException.class;
    }
}
