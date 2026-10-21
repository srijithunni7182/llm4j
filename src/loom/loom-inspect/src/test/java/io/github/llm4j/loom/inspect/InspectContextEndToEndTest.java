package io.github.llm4j.loom.inspect;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.budget.CharsPerTokenEstimator;
import io.github.llm4j.context.ContextWindow;
import io.github.llm4j.context.ContextWindows;
import io.github.llm4j.loom.context.WindowLayers;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.fabric.FabricServer;
import io.github.llm4j.loom.fabric.Vault;
import io.github.llm4j.loom.inspect.emit.Outbound;
import io.github.llm4j.loom.inspect.emit.ServerLink;
import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.state.RunState;
import io.github.llm4j.loom.inspect.state.ToolSummary;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R16: a real Loom run with a context block, a window and a tool that keeps failing, reported
 * through the real add-on to the real Fabric server: the server's view shows context, compactions,
 * models, tool health and the incidents, with no change to the server.
 */
class InspectContextEndToEndTest {

    private static final String SCRIPT =
            """
            agent Researcher {
                model: "tiny-model"
                tools: [Lookup]
                max_iterations: 60
                context { compact: drop_oldest  keep_recent: 2  max_compactions: 50 }
            }
            workflow Main() { delegate "research the topic" to Researcher -> found }
            """;

    private static final int STEPS = 14;

    @TempDir Path dir;

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

    private static void until(BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + 15_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(10);
        assertThat(cond.getAsBoolean()).as("condition within 15s").isTrue();
    }

    private HarnessExecutor executor(String key) {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry();
        tools.register(
                "Lookup",
                new Tool() {
                    @Override
                    public String getName() {
                        return "Lookup";
                    }

                    @Override
                    public String getDescription() {
                        return "always fails, softly";
                    }

                    @Override
                    public String execute(Map<String, Object> args) {
                        return "Error: 404 not found for "
                                + args.get("q")
                                + " "
                                + "padding ".repeat(150);
                    }
                });
        HarnessExecutor e =
                new HarnessExecutor(
                        new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript(),
                        tools,
                        model ->
                                new LLMClient() {
                                    @Override
                                    public LLMResponse chat(LLMRequest request) {
                                        int i = calls.getAndIncrement();
                                        long prompt =
                                                CharsPerTokenEstimator.INSTANCE.prompt(request);
                                        String content =
                                                i < STEPS
                                                        ? "```json\n{\"thought\": \"look\", \"action\": \"Lookup\", \"action_input\": {\"q\": \"topic "
                                                                + i
                                                                + "\"}}\n```"
                                                        : "```json\n{\"thought\": \"t\", \"final_answer\": \"nothing found\"}\n```";
                                        return LLMResponse.builder()
                                                .content(content)
                                                .model(model)
                                                .tokenUsage((int) prompt, 20, (int) prompt + 20)
                                                .build();
                                    }

                                    @Override
                                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                                        return Stream.of(chat(request));
                                    }
                                });
        e.setContextWindows(
                WindowLayers.none()
                        .over(
                                "test",
                                ContextWindows.empty()
                                        .with("tiny-model", new ContextWindow(3000, 500))));
        InMemorySecretStore secrets = new InMemorySecretStore();
        secrets.put("LOOM_FABRIC_CLIENT_KEY", key);
        e.setSecretStore(secrets);
        e.setRunId("r-ctx");
        return e;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRunShowsItsContextModelsToolHealthAndIncidentsAtTheServer() throws Exception {
        String key = Vault.newClientKey();
        int port = freePort();
        Vault vault = Vault.create(dir.resolve("vault.json"), "a-long-enough-master-key");
        vault.put("client.alpha", key);
        vault.put(Vault.ADMIN, "correct horse battery");
        server =
                new FabricServer(
                        new FabricServer.Settings(
                                dir.resolve("data"), "127.0.0.1", port, null, false, 30, 1L << 30),
                        vault,
                        new PrintStream(new ByteArrayOutputStream()));
        server.start();
        Outbound.useTiming(
                new ServerLink.Timing(
                        Duration.ofMillis(10),
                        Duration.ofMillis(100),
                        Duration.ofMillis(20),
                        Duration.ofMillis(100)));
        FabricOptions f =
                FabricOptions.parse(
                        "http://127.0.0.1:" + port,
                        "alpha",
                        null,
                        false,
                        null,
                        dir.resolve("outbox").toString(),
                        null,
                        "3s");
        session =
                (InspectSession)
                        new InspectObserveProvider()
                                .open(
                                        new ObserveOptions(
                                                0, null, "full", "alpha", Duration.ZERO, false, f),
                                        new PrintStream(new ByteArrayOutputStream()));
        HarnessExecutor e = executor(key);
        session.attach(e.observable());
        e.initialize();
        e.executeWorkflow("Main", Map.of());

        until(
                () -> {
                    RunState r = server.registry().findByTrace(Ids.traceId("r-ctx")).orElse(null);
                    return r != null && "finished".equals(r.view().get("status"));
                });
        RunState run = server.registry().findByTrace(Ids.traceId("r-ctx")).orElseThrow();
        Map<String, Object> v = run.view();

        Map<String, Object> agent = ((List<Map<String, Object>>) v.get("agents")).get(0);
        Map<String, Object> ctx = (Map<String, Object>) agent.get("context");
        assertThat((long) ctx.get("compactions")).isPositive();
        assertThat(ctx.get("window")).isEqualTo(3000L);
        assertThat((List<?>) ctx.get("series")).isNotEmpty();

        List<Map<String, Object>> models = (List<Map<String, Object>>) v.get("models");
        assertThat(models)
                .singleElement()
                .satisfies(
                        m -> {
                            assertThat(m.get("name")).isEqualTo("tiny-model");
                            assertThat(m.get("calls")).isEqualTo((long) STEPS + 1);
                        });

        Map<String, Object> tool = ((List<Map<String, Object>>) v.get("tools")).get(0);
        Map<String, Object> health = (Map<String, Object>) tool.get("health");
        assertThat(health.get("state")).isEqualTo("failing");
        assertThat(((Map<?, ?>) health.get("failures")).get("not_found")).isEqualTo((long) STEPS);
        assertThat(String.valueOf(health.get("reason"))).contains("never succeeded");

        List<String> kinds =
                ((List<Map<String, Object>>) v.get("incidents"))
                        .stream().map(i -> String.valueOf(i.get("kind"))).toList();
        assertThat(kinds).contains("TOOL_RATE");

        List<ToolSummary> rollup = server.registry().toolRollup(null);
        assertThat(rollup)
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.name()).isEqualTo("Lookup");
                            assertThat(s.state()).isEqualTo("failing");
                            assertThat(s.byAgent()).containsKey("Researcher");
                        });
    }
}
