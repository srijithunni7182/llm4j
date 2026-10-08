package io.github.llm4j.loom.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.cli.CliProbe;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verification plan V4.1–V4.10 and V3.3–V3.4. */
class ToolsTest {

    @TempDir
    Path dir;

    MockWebServer server;
    final List<String> tasks = Collections.synchronizedList(new ArrayList<>());
    final List<String> systems = Collections.synchronizedList(new ArrayList<>());
    List<String> answers = List.of();

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    static String call(String tool, String inputJson) {
        return "```json\n{\"thought\": \"use " + tool + "\", \"action\": \"" + tool + "\", \"action_input\": " + inputJson + "}\n```";
    }

    static String done(String answer) {
        return "```json\n{\"thought\": \"t\", \"final_answer\": \"" + answer + "\"}\n```";
    }

    LLMClient model() {
        AtomicInteger n = new AtomicInteger();
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest r) {
                systems.add(r.getMessages().get(0).getContent());
                tasks.add(r.getMessages().get(r.getMessages().size() - 1).getContent());
                int i = n.getAndIncrement();
                return LLMResponse.builder().content(i < answers.size() ? answers.get(i) : done("ok")).model("m")
                        .tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest r) {
                return Stream.of(chat(r));
            }
        };
    }

    HarnessExecutor executor(String source, Map<String, String> env) {
        return executor(source, env, new ToolRegistry());
    }

    HarnessExecutor executor(String source, Map<String, String> env, ToolRegistry registry) {
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        LLMClient m = model();
        HarnessExecutor e = new HarnessExecutor(script, registry, x -> m);
        e.setEnvLookup(env::get);
        e.setBaseDir(dir);
        return e;
    }

    @Test
    void v4_1_serpApiWithAKeyFromTheEnvironment() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"organic_results\": [{\"title\": \"Loom 2\", \"link\": \"https://x\", \"snippet\": \"released\"}]}"));
        answers = List.of(call("Search", "{\"query\": \"loom release\"}"), done("found it"));
        HarnessExecutor e = executor("""
                tool Search { use: serpapi  api_key: env.SERPAPI_KEY  base_url: "%s" }
                agent A { model: "m" tools: [Search] }
                workflow Main() { delegate "news?" to A -> x }
                """.formatted(server.url("/search.json")), Map.of("SERPAPI_KEY", "abc123"));
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        RecordedRequest req = server.takeRequest();
        assertThat(req.getRequestUrl().queryParameter("api_key")).isEqualTo("abc123");
        assertThat(req.getRequestUrl().queryParameter("q")).isEqualTo("loom release");
        assertThat(tasks.get(1)).contains("Loom 2");
        assertThat(e.getContext().getVariable("x")).isEqualTo("found it");
    }

    @Test
    void v4_2_aLiteralSecretIsRejected() {
        assertThatThrownBy(() -> executor("""
                tool Search { use: serpapi  api_key: "abc123" }
                agent A { model: "m" tools: [Search] }
                """, Map.of()).initialize())
                .hasMessageContaining("line 1: tool Search: api_key must come from the environment or the secret store, e.g. api_key: secret.SERPAPI_KEY (or env.SERPAPI_KEY)")
                .hasMessageNotContaining("abc123");
    }

    @Test
    void v4_3_missingUnknownAndBadOptions() {
        assertThatThrownBy(() -> executor("""
                tool A1 { use: serpapi }
                tool A2 { use: teleport }
                tool A3 { use: calculator  colour: red }
                """, Map.of()).initialize()).isInstanceOfSatisfying(LoomLoadException.class, ex -> {
            assertThat(ex.getMessage()).contains("line 1: tool A1: use: serpapi needs api_key:")
                    .contains("line 2: tool A2: unknown tool kind 'teleport'")
                    .contains("line 3: tool A3: unknown option colour for use: calculator (it takes none)");
        });
        assertThatThrownBy(() -> new LoomParser(new Lexer("tool T { base_url: \"x\" }").tokenize()).parseScript())
                .hasMessageContaining("tool T needs use: <kind>");
    }

    @Test
    void v4_4_builtInsNeedNoDeclaration() {
        answers = List.of(call("calculator", "{\"expression\": \"6*7\"}"), done("42"));
        HarnessExecutor e = executor("""
                agent A { model: "m" tools: [web_search, calculator, datetime, current_time] }
                workflow Main() { delegate "compute" to A -> x }
                """, Map.of());
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        assertThat(systems.get(0)).contains("web_search").contains("calculator").contains("datetime").contains("current_time");
        assertThat(tasks.get(1)).contains("42");
    }

    @Test
    void v4_4b_duckduckgoWithABaseUrl() throws Exception {
        server.enqueue(new MockResponse().setBody("<html><body>no results</body></html>"));
        server.enqueue(new MockResponse().setBody("<html><body>no results</body></html>"));
        answers = List.of(call("Web", "{\"query\": \"loom\"}"), done("ok"));
        HarnessExecutor e = executor("""
                tool Web { use: duckduckgo  base_url: "%s" }
                agent A { model: "m" tools: [Web] }
                workflow Main() { delegate "q" to A -> x }
                """.formatted(server.url("/lite/")), Map.of());
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        assertThat(server.getRequestCount()).isGreaterThanOrEqualTo(1);
        assertThat(server.takeRequest().getPath()).startsWith("/lite/");
    }

    @Test
    void v4_5_openApiWithHeaderAuth() throws Exception {
        String spec = """
                {"openapi": "3.0.0", "info": {"title": "Pets", "version": "1"},
                 "servers": [{"url": "%s"}],
                 "paths": {"/pets": {"get": {"summary": "List pets",
                    "parameters": [{"name": "limit", "in": "query", "schema": {"type": "integer"}}],
                    "responses": {"200": {"description": "ok"}}}}}}
                """.formatted(server.url("/api").toString().replaceAll("/$", ""));
        Files.writeString(dir.resolve("pets.json"), spec);
        server.enqueue(new MockResponse().setBody("[{\"name\": \"Rex\"}]"));
        answers = List.of(call("Petstore", "{\"endpoint\": \"/pets\", \"method\": \"GET\", \"parameters\": {\"limit\": 2}}"), done("Rex"));
        HarnessExecutor e = executor("""
                tool Petstore { use: openapi  spec: "pets.json"  auth_header: "X-API-Key"  auth_value: env.PET_KEY }
                agent A { model: "m" tools: [Petstore] }
                workflow Main() { delegate "pets?" to A -> x }
                """, Map.of("PET_KEY", "s3cret"));
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        RecordedRequest req = server.takeRequest();
        assertThat(req.getHeader("X-API-Key")).isEqualTo("s3cret");
        assertThat(req.getPath()).startsWith("/api/pets").contains("limit=2");
        assertThat(systems.get(0)).contains("GET /pets: List pets"); // the model is told the endpoints
        assertThat(tasks.get(1)).contains("Rex");
    }

    @Test
    void v4_6_openApiAuthMustBeConsistent() {
        assertThatThrownBy(() -> executor("""
                tool P { use: openapi  spec: "x.json"  auth_value: env.K }
                """, Map.of("K", "v")).initialize()).hasMessageContaining("auth_value goes with exactly one of auth_header or auth_query");
        assertThatThrownBy(() -> executor("""
                tool P { use: openapi  spec: "x.json"  auth_header: "H"  auth_query: "q"  auth_value: env.K }
                """, Map.of("K", "v")).initialize()).hasMessageContaining("use auth_header or auth_query, not both");
    }

    @Test
    void v4_7_anyToolClass() {
        HarnessExecutor ok = executor("""
                tool Mock { use: class  class: "io.github.llm4j.loom.execution.MockTool" }
                agent A { model: "m" tools: [Mock] }
                """, Map.of());
        ok.initialize();
        assertThatThrownBy(() -> executor("""
                tool Bad { use: class  class: "java.lang.String" }
                agent A { model: "m" tools: [Bad] }
                """, Map.of()).initialize()).hasMessageContaining("tool Bad: can't be created").hasMessageContaining("does not implement");
    }

    @Test
    void v4_8_aDeclarationShadowsABuiltIn() {
        answers = List.of(call("calculator", "{}"), done("ok"));
        HarnessExecutor e = executor("""
                tool calculator { use: class  class: "io.github.llm4j.loom.execution.MockTool" }
                agent A { model: "m" tools: [calculator] }
                workflow Main() { delegate "x" to A -> x }
                """, Map.of());
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        assertThat(tasks.get(1)).doesNotContain("Error"); // MockTool ran, not the real calculator
    }

    @Test
    void v4_9_registeredToolsStillWork() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("Echo", new io.github.llm4j.agent.tools.EchoTool());
        executor("agent A { model: \"m\" tools: [Echo] }", Map.of(), registry).initialize();
    }

    @Test
    void v4_10_theModelSeesTheScriptsNames() {
        HarnessExecutor e = executor("""
                tool Search { use: duckduckgo }
                agent A { model: "m" tools: [Search] }
                workflow Main() { delegate "x" to A -> x }
                """, Map.of());
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        assertThat(systems.get(0)).contains("Search").doesNotContain("WebSearch");
    }

    @Test
    void v3_3_and_v3_4_checkNamesMissingVariablesButNeverPrintsSecrets() throws Exception {
        Path f = dir.resolve("s.loom");
        Files.writeString(f, """
                tool Search { use: serpapi  api_key: env.SERPAPI_KEY }
                tool Pets { use: openapi  spec: "none.json"  auth_query: "key"  auth_value: env.PET_KEY }
                agent A { model: "m" tools: [Search, Pets, Nope] }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = CliProbe.check(f.toFile(), false, new PrintStream(out, true),
                Map.of("PET_KEY", "sk-secret-123")::get, new AtomicInteger());
        assertThat(exit).isEqualTo(2);
        assertThat(out.toString()).contains("environment variable SERPAPI_KEY is not set").contains("tool Nope is not defined")
                .doesNotContain("sk-secret-123");
    }

    @Test
    void envReferencesMustBeSimple() {
        assertThatThrownBy(() -> new LoomParser(new Lexer("tool T { use: serpapi api_key: env.A.B }").tokenize()).parseScript())
                .hasMessageContaining("an environment reference is env.NAME");
        assertThatThrownBy(() -> new LoomParser(new Lexer("tool T { use: serpapi api_key: env.K api_key: env.K }").tokenize()).parseScript())
                .hasMessageContaining("option api_key is given twice");
    }

    @Test
    void customToolKinds() {
        HarnessExecutor e = executor("""
                tool Hello { use: greeter  greeting: "hi" }
                agent A { model: "m" tools: [Hello] }
                """, Map.of());
        e.addToolKind(new io.github.llm4j.loom.tools.ToolKind() {
            @Override
            public String name() {
                return "greeter";
            }

            @Override
            public java.util.Set<String> required() {
                return java.util.Set.of("greeting");
            }

            @Override
            public Tool create(String name, Map<String, String> options, Path baseDir) {
                return new io.github.llm4j.agent.tools.EchoTool();
            }
        });
        e.initialize();
    }
}
