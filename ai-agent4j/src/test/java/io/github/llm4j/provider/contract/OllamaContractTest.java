package io.github.llm4j.provider.contract;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.ollama.OllamaProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;

/** The contract for Ollama, with its chat API's wire formats (NDJSON when streaming). */
class OllamaContractTest extends ProviderContract {

    @Override
    protected LLMProvider provider(String model) {
        return new OllamaProvider(config(url("/api"), model));
    }

    @Override
    protected String model() {
        return "llama3.2";
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
        JsonNode o = r.path("options");
        return new Settings(integer(o.path("num_predict")), decimal(o.path("temperature")), decimal(o.path("top_p")),
                strings(o.path("stop")));
    }

    static String done(String reason, String text, int in, int out) {
        return "{\"model\":\"llama3.2\",\"message\":{\"role\":\"assistant\",\"content\":" + AnthropicContractTest.quote(text)
                + "},\"done\":true,\"done_reason\":\"" + reason + "\",\"prompt_eval_count\":" + in + ",\"eval_count\":" + out + "}";
    }

    @Override
    protected String answer(String text, int in, int out) {
        return done("stop", text, in, out);
    }

    @Override
    protected String truncated(String text) {
        return done("length", text, 10, 5);
    }

    @Override
    protected String refusal(String category) {
        return null; // Ollama has no refusal signal
    }

    @Override
    protected String error(int status, String message) {
        return "{\"error\":" + AnthropicContractTest.quote(message) + "}";
    }

    @Override
    protected MockResponse rateLimited(Instant reset) {
        return new MockResponse().setResponseCode(429)
                .setHeader("Retry-After", String.valueOf(Duration.between(Instant.now(), reset).getSeconds()))
                .setBody(error(429, "too many requests"));
    }

    static String line(String text) {
        return "{\"model\":\"llama3.2\",\"message\":{\"role\":\"assistant\",\"content\":" + AnthropicContractTest.quote(text) + "},\"done\":false}\n";
    }

    @Override
    protected MockResponse stream(List<String> pieces, int in, int out) {
        StringBuilder sb = new StringBuilder();
        pieces.forEach(p -> sb.append(line(p)));
        sb.append(done("stop", "", in, out)).append('\n');
        return new MockResponse().setHeader("Content-Type", "application/x-ndjson").setBody(sb.toString());
    }

    @Override
    protected MockResponse streamThenError(List<String> pieces) {
        StringBuilder sb = new StringBuilder();
        pieces.forEach(p -> sb.append(line(p)));
        sb.append("{\"error\":\"model runner has unexpectedly stopped\"}\n");
        return new MockResponse().setHeader("Content-Type", "application/x-ndjson").setBody(sb.toString());
    }

    @Override
    protected Class<? extends RuntimeException> midStreamError() {
        return ProviderException.class;
    }
}
