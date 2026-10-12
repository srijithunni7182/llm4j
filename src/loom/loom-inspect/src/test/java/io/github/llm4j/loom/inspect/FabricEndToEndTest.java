package io.github.llm4j.loom.inspect;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.fabric.FabricServer;
import io.github.llm4j.loom.fabric.Vault;
import io.github.llm4j.loom.inspect.emit.Outbound;
import io.github.llm4j.loom.inspect.emit.ServerLink;
import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.state.RunState;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.observe.FabricOptions;
import io.github.llm4j.loom.observe.ObserveOptions;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.secret.InMemorySecretStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real executor, the real add-on and the real Fabric server over loopback: a run reaches the server; a server that is down during
 * the run gets the whole run when it returns; a wrong key stops the reporting and not the workflow.
 */
class FabricEndToEndTest {

    private static final String SCRIPT = """
            agent Researcher { model: "m" tools: [Lookup] }
            agent Writer { model: "m" }
            workflow Main() {
                delegate "find the answer" to Researcher -> found
                delegate "write it up: {found}" to Writer -> text
            }
            """;

    @TempDir
    Path dir;

    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private FabricServer server;
    private InspectSession session;

    @AfterEach
    void stop() {
        Outbound.useTiming(null);
        if (session != null) session.close();
        if (server != null) server.close();
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private FabricServer fabric(int port, Vault vault) {
        FabricServer s = new FabricServer(new FabricServer.Settings(dir.resolve("data"), "127.0.0.1", port, null, false, 30, 1L << 30), vault, new PrintStream(console));
        s.start();
        return s;
    }

    private Vault vault(String key) {
        Vault v = Vault.create(dir.resolve("vault-" + System.nanoTime() + ".json"), "a-long-enough-master-key");
        v.put("client.alpha", key);
        v.put(Vault.ADMIN, "correct horse battery");
        return v;
    }

    private InspectSession openSession(int port) {
        Outbound.useTiming(new ServerLink.Timing(Duration.ofMillis(10), Duration.ofMillis(100), Duration.ofMillis(20), Duration.ofMillis(100)));
        FabricOptions f = FabricOptions.parse("http://127.0.0.1:" + port, "alpha", null, false, null, dir.resolve("outbox").toString(), null, "3s");
        ObserveOptions o = new ObserveOptions(0, null, "full", "alpha", Duration.ZERO, false, f);
        return (InspectSession) new InspectObserveProvider().open(o, new PrintStream(console));
    }

    private HarnessExecutor executor(String key) {
        int[] next = {0};
        List<String> answers = List.of(
                "```json\n{\"thought\": \"use Lookup\", \"action\": \"Lookup\", \"action_input\": {\"q\": \"x\"}}\n```",
                "```json\n{\"thought\": \"t\", \"final_answer\": \"forty-two\"}\n```",
                "```json\n{\"thought\": \"t\", \"final_answer\": \"a write-up\"}\n```");
        LLMClientFactory factory = model -> new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                return LLMResponse.builder().content(answers.get(next[0]++)).model(model).tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
        ToolRegistry tools = new ToolRegistry();
        tools.register("Lookup", new Tool() {
            @Override public String getName() { return "Lookup"; }
            @Override public String getDescription() { return "test"; }
            @Override public String execute(Map<String, Object> args) { return "the answer is 42"; }
        });
        HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript(), tools, factory);
        InMemorySecretStore secrets = new InMemorySecretStore();
        secrets.put("LOOM_FABRIC_CLIENT_KEY", key);
        e.setSecretStore(secrets);
        e.setRunId("r-e2e");
        return e;
    }

    private static void until(BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(10);
        assertThat(cond.getAsBoolean()).as("condition within 10s").isTrue();
    }

    private RunState onServer() {
        return server.registry().findByTrace(Ids.traceId("r-e2e")).orElse(null);
    }

    private static boolean finishedWithTokens(RunState r, long tokens) {
        if (r == null) return false;
        Map<String, Object> v = r.view();
        return "finished".equals(v.get("status")) && ((Map<?, ?>) v.get("totals")).get("tokens").equals(tokens);
    }

    @Test
    void aRunReachesTheServerWithItsStepsGraphAndProcess() throws Exception {
        String key = Vault.newClientKey();
        int port = freePort();
        server = fabric(port, vault(key));
        session = openSession(port);
        HarnessExecutor e = executor(key);
        session.attach(e.observable());
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        until(() -> finishedWithTokens(onServer(), 45L));

        Map<String, Object> v = onServer().view();
        assertThat(v).containsEntry("client", "alpha").containsEntry("workflow", "Main");
        assertThat((List<?>) v.get("agents")).hasSize(2);
        assertThat(onServer().graph()).containsKey("workflows");
        Map<String, Object> p = server.receiver().processes().get(0);
        assertThat(p).containsEntry("name", "alpha").containsEntry("state", "online");
        assertThat(((List<?>) p.get("workflows")).contains("Main")).isTrue();
    }

    @Test
    void aServerThatIsDownDuringTheRunGetsTheWholeRunWhenItReturns_andTheRunWasNeverAffected() throws Exception {
        String key = Vault.newClientKey();
        int port = freePort(); // nothing listens on it yet
        session = openSession(port);
        HarnessExecutor e = executor(key);
        session.attach(e.observable());
        e.initialize();
        e.executeWorkflow("Main", Map.of()); // completes with the server down: an exception here fails the test
        Thread.sleep(150);
        assertThat(session.registry().summaries()).isEmpty(); // not embedded: nothing local either

        server = fabric(port, vault(key)); // the server comes up late
        until(() -> finishedWithTokens(onServer(), 45L));
        Map<String, Object> v = onServer().view();
        assertThat(v.get("gaps")).isEqualTo(0L);
        assertThat((List<?>) v.get("agents")).hasSize(2);
        assertThat(console.toString()).contains("cannot reach the Fabric server");
    }

    @Test
    void aWrongKeyStopsTheReportingAndNotTheWorkflow() throws Exception {
        int port = freePort();
        server = fabric(port, vault(Vault.newClientKey()));
        session = openSession(port);
        HarnessExecutor e = executor(Vault.newClientKey()); // not the key the server has
        session.attach(e.observable());
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        until(() -> console.toString().contains("rejected this process's key"));
        assertThat(onServer()).isNull();
    }

    @Test
    void withNoKeyInTheSecretStoreTheRunStillWorks_andTheMessageSaysWhatToDo() throws Exception {
        int port = freePort();
        session = openSession(port);
        HarnessExecutor e = executor("placeholder");
        ((InMemorySecretStore) e.observable().secretStore()).remove("LOOM_FABRIC_CLIENT_KEY");
        session.attach(e.observable());
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        assertThat(console.toString()).contains("not reporting").contains("weave secrets set LOOM_FABRIC_CLIENT_KEY");
    }

    @Test
    void otlpAloneExportsARealRunToAStandardEndpoint_asServiceNameAndSteps() throws Exception {
        List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer collector = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        collector.createContext("/", x -> {
            bodies.add(x.getRequestURI().getPath() + " " + x.getRequestHeaders().getFirst("Authorization") + " " + new String(x.getRequestBody().readAllBytes()));
            x.sendResponseHeaders(200, -1);
            x.close();
        });
        collector.start();
        try {
            Outbound.useTiming(new ServerLink.Timing(Duration.ofMillis(10), Duration.ofMillis(100), Duration.ofMillis(20), Duration.ofMillis(100)));
            ObserveOptions o = ObserveOptions.fromCommandLine(null, null, "full", "alpha", null, null, null, false, null, dir.resolve("outbox").toString(), null, null,
                    "http://127.0.0.1:" + collector.getAddress().getPort(), List.of("Authorization=Bearer ${secret:OTLP_TOKEN}"), k -> null);
            session = (InspectSession) new InspectObserveProvider().open(o, new PrintStream(console));
            HarnessExecutor e = executor("unused");
            ((InMemorySecretStore) e.observable().secretStore()).put("OTLP_TOKEN", "tok-123");
            session.attach(e.observable());
            e.initialize();
            e.executeWorkflow("Main", Map.of());
            until(() -> bodies.stream().anyMatch(b -> b.startsWith("/v1/traces")) && bodies.stream().anyMatch(b -> b.startsWith("/v1/logs")));
            assertThat(bodies).allSatisfy(b -> assertThat(b).contains("Bearer tok-123"));
            assertThat(String.join("\n", bodies)).contains("\"service.name\"").contains("alpha").contains("invoke_agent Researcher").contains("execute_tool Lookup");
        } finally {
            collector.stop(0);
        }
    }

    @Test
    void aStopFromTheFabricPageReachesTheRunInAnotherProcessAndEndsIt() throws Exception {
        String key = Vault.newClientKey();
        server = fabric(freePort(), vault(key));
        session = openSession(server.port());
        HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer("""
                workflow Main() {
                    note "before"
                    sleep 5s
                    note "after"
                }
                """).tokenize()).parseScript(), new ToolRegistry(), model -> null);
        InMemorySecretStore secrets = new InMemorySecretStore();
        secrets.put("LOOM_FABRIC_CLIENT_KEY", key);
        e.setSecretStore(secrets);
        e.setRunId("r-e2e");
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        e.setSleeper(d -> release.await());
        session.attach(e.observable());
        e.initialize();
        java.util.concurrent.atomic.AtomicReference<Throwable> ended = new java.util.concurrent.atomic.AtomicReference<>();
        Thread run = new Thread(() -> {
            try {
                e.executeWorkflow("Main", Map.of());
            } catch (Throwable t) {
                ended.set(t);
            }
        });
        run.start();
        until(() -> onServer() != null && !server.receiver().processes().isEmpty() && "online".equals(server.receiver().processes().get(0).get("state")));

        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + server.port();
        java.net.http.HttpResponse<String> login = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/login")).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"password\":\"correct horse battery\"}")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        String csrf = new com.fasterxml.jackson.databind.ObjectMapper().readTree(login.body()).get("csrf").asText();
        java.net.http.HttpResponse<String> stop = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/runs/" + Ids.traceId("r-e2e") + "/stop"))
                .header("Cookie", cookie).header("X-CSRF-Token", csrf).header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(stop.statusCode()).isEqualTo(202);

        // the command travels on the process's long-poll; the run is in its sleep step and sees the stop at the next step
        until(() -> e.control().state().pending() != null);
        release.countDown();
        run.join(5000);
        assertThat(run.isAlive()).isFalse();
        assertThat(ended.get()).isInstanceOf(io.github.llm4j.loom.runtime.RunStopped.class);
        until(() -> onServer() != null && "stopped".equals(onServer().view().get("status")));
        assertThat(java.nio.file.Files.readString(dir.resolve("data").resolve("audit.log"))).contains("\"action\":\"stop\"").contains("command-result").doesNotContain("correct horse");
    }
}
