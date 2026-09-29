package io.github.llm4j.provider.contract;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.sarvam.SarvamChatProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;

/**
 * The contract for Sarvam chat (OpenAI-style wire formats). Its reasoning model writes a leading {@code
 * <think>} block, which must not reach the answer.
 */
class SarvamContractTest extends ProviderContract {

    @Override
    protected LLMProvider provider(String model) {
        return new SarvamChatProvider(config(url(""), model));
    }

    @Override
    protected String model() {
        return "sarvam-m";
    }

    @Override
    protected String nativeSystem(JsonNode request) {
        List<String> system = new ArrayList<>();
        request.path("messages").forEach(m -> {
            if ("system".equals(m.path("role").asText())) system.add(m.path("content").asText());
        });
        return system.isEmpty() ? null : String.join("\n\n", system);
    }

    @Override
    protected List<String> turns(JsonNode request) {
        List<String> out = new ArrayList<>();
        request.path("messages").forEach(m -> {
            if (!"system".equals(m.path("role").asText())) out.add(m.path("role").asText() + ": " + m.path("content").asText());
        });
        return out;
    }

    @Override
    protected Settings settings(JsonNode r) {
        return new Settings(integer(r.path("max_tokens")), decimal(r.path("temperature")), decimal(r.path("top_p")),
                strings(r.path("stop")));
    }

    static String completion(String reason, String text, int in, int out) {
        return """
                {"id":"chatcmpl-1","object":"chat.completion","model":"sarvam-m",
                 "choices":[{"index":0,"message":{"role":"assistant","content":%s},"finish_reason":"%s"}],
                 "usage":{"prompt_tokens":%d,"completion_tokens":%d,"total_tokens":%d}}"""
                .formatted(AnthropicContractTest.quote("<think>\nThe user wants a fact.\n</think>\n\n" + text), reason, in, out, in + out);
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
        return null; // Sarvam has no refusal signal
    }

    @Override
    protected String error(int status, String message) {
        return "{\"error\":{\"message\":" + AnthropicContractTest.quote(message) + ",\"code\":\"error_" + status + "\"}}";
    }

    @Override
    protected MockResponse rateLimited(Instant reset) {
        return new MockResponse().setResponseCode(429)
                .setHeader("Retry-After", String.valueOf(Duration.between(Instant.now(), reset).getSeconds()))
                .setBody(error(429, "rate limit exceeded"));
    }

    static String data(String json) {
        return "data: " + json + "\n\n";
    }

    static String delta(String text) {
        return "{\"choices\":[{\"index\":0,\"delta\":{\"content\":" + AnthropicContractTest.quote(text) + "},\"finish_reason\":null}]}";
    }

    @Override
    protected MockResponse stream(List<String> pieces, int in, int out) {
        StringBuilder sb = new StringBuilder(data(delta("<think>short plan")));
        sb.append(data(delta("</think>\n\n")));
        pieces.forEach(p -> sb.append(data(delta(p))));
        sb.append(data("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":" + in + ",\"completion_tokens\":" + out + ",\"total_tokens\":" + (in + out) + "}}"));
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
}
