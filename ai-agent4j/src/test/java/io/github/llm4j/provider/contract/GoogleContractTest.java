package io.github.llm4j.provider.contract;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.exception.ServiceUnavailableException;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;

/** The contract for Gemini, with the Generative Language API's wire formats. */
class GoogleContractTest extends ProviderContract {

    @Override
    protected LLMProvider provider(String model) {
        return new GoogleProvider(config(url("/v1beta"), model));
    }

    @Override
    protected String model() {
        return "gemini-2.5-flash";
    }

    @Override
    protected String nativeSystem(JsonNode request) {
        JsonNode parts = request.path("systemInstruction").path("parts");
        if (!parts.isArray()) return null;
        StringBuilder sb = new StringBuilder();
        parts.forEach(p -> sb.append(p.path("text").asText()));
        return sb.toString();
    }

    @Override
    protected List<String> turns(JsonNode request) {
        List<String> out = new ArrayList<>();
        request.path("contents").forEach(c -> {
            StringBuilder sb = new StringBuilder();
            c.path("parts").forEach(p -> sb.append(p.path("text").asText()));
            out.add(("model".equals(c.path("role").asText()) ? "assistant" : "user") + ": " + sb);
        });
        return out;
    }

    @Override
    protected Settings settings(JsonNode r) {
        JsonNode g = r.path("generationConfig");
        return new Settings(integer(g.path("maxOutputTokens")), decimal(g.path("temperature")), decimal(g.path("topP")),
                strings(g.path("stopSequences")));
    }

    static String candidate(String finish, String text, int in, int out) {
        return """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"Let me think.","thought":true},{"text":%s}]},
                  "finishReason":"%s","index":0}],
                 "usageMetadata":{"promptTokenCount":%d,"candidatesTokenCount":%d,"totalTokenCount":%d},
                 "modelVersion":"gemini-2.5-flash"}""".formatted(AnthropicContractTest.quote(text), finish, in, out, in + out);
    }

    @Override
    protected String answer(String text, int in, int out) {
        return candidate("STOP", text, in, out);
    }

    @Override
    protected String truncated(String text) {
        return candidate("MAX_TOKENS", text, 10, 5);
    }

    @Override
    protected String refusal(String category) {
        return """
                {"candidates":[{"content":{"role":"model","parts":[]},"finishReason":"SAFETY",
                  "safetyRatings":[{"category":"HARM_CATEGORY_DANGEROUS_CONTENT","probability":"HIGH"}]}]}""";
    }

    @Override
    protected String error(int status, String message) {
        String s = switch (status) {
            case 401 -> "UNAUTHENTICATED";
            case 503 -> "UNAVAILABLE";
            default -> "INVALID_ARGUMENT";
        };
        return "{\"error\":{\"code\":" + status + ",\"message\":" + AnthropicContractTest.quote(message) + ",\"status\":\"" + s + "\"}}";
    }

    @Override
    protected MockResponse rateLimited(Instant reset) {
        long seconds = Duration.between(Instant.now(), reset).getSeconds();
        return new MockResponse().setResponseCode(429).setBody("""
                {"error":{"code":429,"message":"Resource has been exhausted (e.g. check quota).","status":"RESOURCE_EXHAUSTED",
                  "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"%ds"}]}}""".formatted(seconds));
    }

    static String data(String json) {
        return "data: " + json.replace("\n", "") + "\r\n\r\n";
    }

    static String chunk(String text) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":" + AnthropicContractTest.quote(text) + "}]},\"index\":0}]}";
    }

    @Override
    protected MockResponse stream(List<String> pieces, int in, int out) {
        StringBuilder sb = new StringBuilder();
        sb.append(data("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"planning\",\"thought\":true}]}}]}"));
        for (int i = 0; i < pieces.size() - 1; i++) sb.append(data(chunk(pieces.get(i))));
        sb.append(data(candidate("STOP", pieces.get(pieces.size() - 1), in, out).replace("{\"text\":\"Let me think.\",\"thought\":true},", "")));
        return sse(sb.toString());
    }

    @Override
    protected MockResponse streamThenError(List<String> pieces) {
        StringBuilder sb = new StringBuilder();
        pieces.forEach(p -> sb.append(data(chunk(p))));
        sb.append(data("{\"error\":{\"code\":503,\"message\":\"The model is overloaded.\",\"status\":\"UNAVAILABLE\"}}"));
        return sse(sb.toString());
    }

    /** Seen from the real API: Gemini answers a bad key with 400, not 401 — still an authentication failure. */
    @org.junit.jupiter.api.Test
    void aBadKeyIsAnAuthenticationFailureEvenAsA400() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("""
                {"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT",
                  "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID","domain":"googleapis.com"}]}}"""));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider(model()).chat(ask("hi")))
                .isExactlyInstanceOf(io.github.llm4j.exception.AuthenticationException.class)
                .hasMessageContaining("API key not valid");
    }

    @Override
    protected Class<? extends RuntimeException> midStreamError() {
        return ServiceUnavailableException.class;
    }
}
