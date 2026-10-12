package io.github.llm4j.loom.invoker;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * What the invoker-credential tests share: a downstream API on localhost that records what it is sent, an OpenAPI description of it, and a
 * model that answers from a script, one agent at a time.
 */
final class InvokerHarness implements AutoCloseable {

    /** An agent that reads {@code Refunds} with the token of whoever runs the workflow. */
    static final String SCRIPT = """
            tool Refunds { use: openapi  spec: "refunds.json"  auth_header: "Authorization"  auth_value: invoker.token }
            agent Scout { model: "m"  system: "You are Scout."  tools: [Refunds] }
            workflow Main() { delegate "refunds?" to Scout -> x }
            """;

    final MockWebServer api = new MockWebServer();
    final List<RecordedRequest> requests = Collections.synchronizedList(new java.util.ArrayList<>());
    /** Every message of every model call, so tests can look for what must never be there. */
    final List<String> modelSaw = Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile Function<RecordedRequest, MockResponse> responder = r -> new MockResponse().setBody("{}");
    private final Map<String, Deque<String>> answers = new ConcurrentHashMap<>();
    private volatile Function<List<String>, String> thinking;
    final Path dir;

    InvokerHarness(Path dir) throws IOException {
        this.dir = dir;
        api.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                requests.add(request);
                return responder.apply(request);
            }
        });
        api.start();
        Files.writeString(dir.resolve("refunds.json"), """
                {"openapi": "3.0.0", "info": {"title": "Refunds", "version": "1"},
                 "servers": [{"url": "%s"}],
                 "paths": {"/refunds": {
                    "get": {"summary": "List refunds",
                      "parameters": [{"name": "who", "in": "query", "schema": {"type": "string"}}],
                      "responses": {"200": {"description": "ok"}}},
                    "post": {"summary": "Create a refund", "responses": {"200": {"description": "ok"}}}}}}
                """.formatted(api.url("/").toString().replaceAll("/$", "")));
    }

    InvokerHarness respond(Function<RecordedRequest, MockResponse> responder) {
        this.responder = responder;
        return this;
    }

    /** What the named agent says on its 1st, 2nd, … model call; after that it says "ok". */
    InvokerHarness agent(String name, String... says) {
        answers.put(name, new ArrayDeque<>(List.of(says)));
        return this;
    }

    /** Every agent answers by looking at the whole conversation (for tests where several runs share the model). */
    InvokerHarness thinks(Function<List<String>, String> thinking) {
        this.thinking = thinking;
        return this;
    }

    static String call(String tool, String inputJson) {
        return "```json\n{\"thought\": \"use " + tool + "\", \"action\": \"" + tool + "\", \"action_input\": " + inputJson + "}\n```";
    }

    static String done(String answer) {
        return "```json\n{\"thought\": \"t\", \"final_answer\": \"" + answer + "\"}\n```";
    }

    static String listRefunds(String who) {
        return call("Refunds", "{\"endpoint\": \"/refunds\", \"method\": \"GET\", \"parameters\": {\"who\": \"" + who + "\"}}");
    }

    private LLMClient model() {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest r) {
                String system = r.getMessages().get(0).getContent();
                r.getMessages().forEach(m -> modelSaw.add(m.getContent()));
                String reply = null;
                if (thinking != null) {
                    reply = thinking.apply(r.getMessages().stream().map(m -> m.getContent()).toList());
                }
                for (Map.Entry<String, Deque<String>> e : answers.entrySet()) {
                    if (thinking == null && system.contains("You are " + e.getKey() + ".")) {
                        synchronized (e.getValue()) {
                            reply = e.getValue().poll();
                        }
                    }
                }
                return LLMResponse.builder().content(reply != null ? reply : done("ok")).model("m").tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest r) {
                return Stream.of(chat(r));
            }
        };
    }

    /** An executor for {@code source} that has not been initialized. */
    HarnessExecutor executor(String source) {
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        LLMClient model = model();
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), x -> model);
        executor.setEnvLookup(Map.<String, String>of()::get);
        executor.setBaseDir(dir);
        return executor;
    }

    /** The Authorization headers the API saw, in arrival order. */
    List<String> authorizations() {
        synchronized (requests) {
            return requests.stream().map(r -> r.getHeader("Authorization")).toList();
        }
    }

    @Override
    public void close() throws IOException {
        api.shutdown();
    }
}
