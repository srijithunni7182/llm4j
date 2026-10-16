package io.github.llm4j.spring;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** What the starter's tests share: a context runner, and provider stand-ins that answer in each provider's own shape and record what they were sent. */
final class Fixtures {

    static final String CANARY = "sk-canary-7f3a9c41";

    private Fixtures() { }

    static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                Llm4jGuardAutoConfiguration.class, Llm4jSecretsAutoConfiguration.class, Llm4jClientsAutoConfiguration.class, Llm4jToolsAutoConfiguration.class,
                Llm4jAgentsAutoConfiguration.class, Llm4jRagAutoConfiguration.class, Llm4jObserveAutoConfiguration.class, Llm4jLoomAutoConfiguration.class));
    }

    static LLMResponse ask(LLMClient client, String text) {
        return client.chat(LLMRequest.builder().addMessage(Message.user(text)).build());
    }

    /** A provider's HTTP API on localhost: answers {@code reply} (or whatever {@link #responder} says) in the shape of {@code kind}, and records requests. */
    static final class Provider implements AutoCloseable {
        final MockWebServer server = new MockWebServer();
        final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();
        volatile String reply = "hello";
        /** Report prompt tokens as a real provider would (a quarter of the request's characters) instead of a constant 10. */
        volatile boolean realisticUsage;
        volatile Function<RecordedRequest, MockResponse> responder;
        final String kind;

        Provider(String kind) throws IOException {
            this.kind = kind;
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    seen.add(request);
                    Function<RecordedRequest, MockResponse> r = responder;
                    return r != null ? r.apply(request) : okFor(reply, request);
                }
            });
            server.start();
        }

        String url() {
            return server.url("/").toString().replaceAll("/$", "");
        }

        MockResponse ok(String text) {
            return ok(text, 10);
        }

        MockResponse okFor(String text, RecordedRequest request) {
            return ok(text, realisticUsage ? Math.max(10, (int) (request.getBodySize() / 4)) : 10);
        }

        MockResponse ok(String text, int promptTokens) {
            String t = quote(text);
            String body = switch (kind) {
                case "anthropic" -> "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude\",\"content\":[{\"type\":\"text\",\"text\":" + t
                        + "}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":" + promptTokens + ",\"output_tokens\":5}}";
                case "gemini" -> "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + t + "}],\"role\":\"model\"},\"finishReason\":\"STOP\"}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":" + promptTokens + ",\"candidatesTokenCount\":5,\"totalTokenCount\":15}}";
                case "sarvam" -> "{\"id\":\"1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":" + t
                        + "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":" + promptTokens + ",\"completion_tokens\":5,\"total_tokens\":15}}";
                default -> "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":" + t + "},\"done\":true,\"prompt_eval_count\":" + promptTokens + ",\"eval_count\":5}";
            };
            return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
        }

        private static String quote(String text) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(text);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Answers these, one for each request, then {@link #reply}. */
        Provider queue(String... replies) {
            java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
            responder = r -> okFor(n.get() < replies.length ? replies[n.getAndIncrement()] : reply, r);
            return this;
        }

        static String react(String tool, String inputJson) {
            return "```json\n{\"thought\": \"use " + tool + "\", \"action\": \"" + tool + "\", \"action_input\": " + inputJson + "}\n```";
        }

        static String done(String answer) {
            return "```json\n{\"thought\": \"done\", \"final_answer\": \"" + answer + "\"}\n```";
        }

        String allBodies() {
            StringBuilder sb = new StringBuilder();
            for (RecordedRequest r : seen) sb.append(r.getBody().clone().readUtf8()).append('\n');
            return sb.toString();
        }

        String lastBody() {
            return seen.isEmpty() ? "" : seen.get(seen.size() - 1).getBody().clone().readUtf8();
        }

        String lastPath() {
            return seen.isEmpty() ? "" : seen.get(seen.size() - 1).getPath();
        }

        @Override
        public void close() throws IOException {
            server.shutdown();
        }
    }
}
