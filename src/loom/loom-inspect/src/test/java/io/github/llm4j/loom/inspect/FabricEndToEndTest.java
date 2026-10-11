package io.github.llm4j.loom.inspect;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.fabric.FabricServer;
import io.github.llm4j.loom.fabric.Vault;
import io.github.llm4j.loom.inspect.emit.FabricLink;
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
        FabricLink.useTiming(null);
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
        FabricLink.useTiming(new ServerLink.Timing(Duration.ofMillis(10), Duration.ofMillis(100), Duration.ofMillis(20), Duration.ofMillis(100)));
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
        assertThat(console.toString()).contains("not reporting to Fabric").contains("weave secrets set LOOM_FABRIC_CLIENT_KEY");
    }
}
