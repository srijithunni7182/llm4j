package io.github.llm4j.loom.field;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * A fake model provider on loopback that records every request body and answers from a function, so
 * a test can assert on what actually left the process. It speaks the OpenAI chat-completions shape
 * ({@code openai_compatible}). It never reaches the network.
 */
public final class RecordingModelServer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One request as it arrived: its path and parsed JSON body. */
    public record Recorded(String path, JsonNode body) {
        /** The text of the first system-level message, or "" when the request has none. */
        public String systemText() {
            JsonNode messages = body.path("messages");
            for (JsonNode m : messages) {
                String role = m.path("role").asText();
                if (role.equals("system") || role.equals("developer"))
                    return m.path("content").asText();
            }
            return "";
        }

        /** Every message's text joined, system and user, in order. */
        public String allText() {
            StringBuilder all = new StringBuilder();
            for (JsonNode m : body.path("messages")) all.append(m.path("content").asText()).append('\n');
            return all.toString();
        }

        /** The output-limit field the request carried, or -1 when it carried none. */
        public long outputLimit() {
            for (String field : new String[] {"max_tokens", "max_completion_tokens"})
                if (body.has(field)) return body.get(field).asLong();
            return -1;
        }
    }

    /** How the fake answers one request: the text it says and the finish reason. */
    public record Answer(
            String text, String finishReason, Integer promptTokens, Integer completionTokens, int status) {
        public static Answer say(String text) {
            return new Answer(text, "stop", 100, 50, 200);
        }

        public static Answer cut(String text) {
            return new Answer(text, "length", 100, 50, 200);
        }

        public Answer withoutUsage() {
            return new Answer(text, finishReason, null, null, status);
        }
    }

    /** A bad request that is not retried, so a test does not wait on backoff. */
    public static Answer reject() {
        return new Answer("{\"error\":{\"message\":\"rejected by the fake\"}}", "", null, null, 400);
    }

    private final MockWebServer server = new MockWebServer();
    private final List<Recorded> recorded = Collections.synchronizedList(new ArrayList<>());

    public RecordingModelServer(Function<Recorded, Answer> answers) throws IOException {
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        try {
                            JsonNode body = JSON.readTree(request.getBody().readUtf8());
                            Recorded r = new Recorded(request.getPath(), body);
                            recorded.add(r);
                            return reply(answers.apply(r));
                        } catch (IOException e) {
                            return new MockResponse().setResponseCode(500).setBody(e.toString());
                        }
                    }
                });
        server.start();
    }

    private static MockResponse reply(Answer a) throws IOException {
        if (a.status() != 200)
            return new MockResponse().setResponseCode(a.status()).setBody(a.text());
        var message = JSON.createObjectNode().put("role", "assistant").put("content", a.text());
        var choice =
                JSON.createObjectNode().put("index", 0).put("finish_reason", a.finishReason());
        choice.set("message", message);
        var root = JSON.createObjectNode().put("id", "fake").put("model", "m");
        root.putArray("choices").add(choice);
        if (a.promptTokens() != null)
            root.putObject("usage")
                    .put("prompt_tokens", a.promptTokens())
                    .put("completion_tokens", a.completionTokens())
                    .put("total_tokens", a.promptTokens() + a.completionTokens());
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(JSON.writeValueAsString(root));
    }

    /** The base URL to put in a script's {@code provider} block. */
    public String baseUrl() {
        return server.url("/v1").toString().replaceAll("/$", "");
    }

    /** Everything received so far, oldest first. */
    public List<Recorded> requests() {
        synchronized (recorded) {
            return List.copyOf(recorded);
        }
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
    }
}
