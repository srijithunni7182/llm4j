package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.cli.CliProbe;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.security.Finding;
import io.github.llm4j.loom.security.SecurityAudit;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 8k in Loom: a {@code pplx-decider-…} model is a valid backend for an {@code intentclassifier}, is
 * not a chat model, takes the Perplexity key, and is listed as a data flow by the audit.
 */
class PerplexityDecisionsLoomTest {

    @TempDir Path dir;

    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private static final String SCRIPT =
            """
            intentclassifier Intent {
                model: "pplx-decider-flash-latest"
                choose kind: refund | order_status | other
                ask urgent: "Is the customer threatening to leave?"
                confidence at least 0.5 else escalate
            }
            workflow Triage(message) {
                intentclassify Intent on message -> intent
                note "route:{intent.kind}"
            }
            """;

    /** What the real API answers (shape recorded 2026-10-22), for these two questions. */
    private static final String REPLY =
            "{\"model\":\"pplx-decider-flash-latest\",\"answers\":{"
                    + "\"kind\":{\"type\":\"choice\",\"choice\":\"refund\",\"confidence\":0.9,\"probabilities\":{\"refund\":0.95,\"order_status\":0.03,\"other\":0.02}},"
                    + "\"urgent\":{\"type\":\"noul\",\"noul\":0.2}},"
                    + "\"usage\":{\"input_tokens\":120,\"output_tokens\":2}}";

    @Test
    void itRunsAgainstThePerplexityEndpointWithThePerplexityKey() throws Exception {
        server.enqueue(
                new MockResponse().setHeader("Content-Type", "application/json").setBody(REPLY));
        LoomScript parsed = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        HarnessExecutor e =
                new HarnessExecutor(
                        parsed,
                        new ToolRegistry(),
                        m -> {
                            throw new IllegalStateException("no chat model is used");
                        });
        Map<String, String> env =
                Map.of(
                        "PERPLEXITY_API_KEY",
                        "pplx-test-key",
                        "PERPLEXITY_BASE_URL",
                        server.url("/").toString().replaceAll("/$", ""));
        e.setEnvLookup(env::get);
        java.util.List<TraceEvent> events =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        e.addTraceListener(events::add);
        e.initialize();
        e.executeWorkflow("Triage", Map.of("message", "I want my money back"));

        var sent = server.takeRequest();
        assertThat(sent.getPath()).isEqualTo("/v1/decisions");
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer pplx-test-key");
        assertThat(sent.getBody().readUtf8()).contains("\"model\":\"pplx-decider-flash-latest\"");
        assertThat(events)
                .anySatisfy(
                        ev -> {
                            assertThat(ev.type()).isEqualTo(TraceEvent.NOTE);
                            assertThat(ev.text()).isEqualTo("route:refund");
                        });
    }

    private int check(String source, Map<String, String> env, ByteArrayOutputStream out)
            throws Exception {
        Path f = dir.resolve("s.loom");
        Files.writeString(f, source);
        return CliProbe.check(
                f.toFile(),
                new PrintStream(out, true),
                env::get,
                new LLMClientFactory() {
                    @Override
                    public io.github.llm4j.LLMClient createClient(String modelName) {
                        throw new IllegalStateException();
                    }

                    @Override
                    public String problem(String modelName) {
                        return null;
                    }
                });
    }

    @Test
    void itNeedsThePerplexityKeyAndIsNotAChatModel() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(check(SCRIPT, Map.of(), out)).isNotZero();
        assertThat(out.toString())
                .contains("intentclassifier Intent")
                .contains("PERPLEXITY_API_KEY");
        out.reset();
        assertThat(check(SCRIPT, Map.of("PERPLEXITY_API_KEY", "x"), out))
                .as(out.toString())
                .isZero();
        out.reset();
        String agent =
                "agent A { model: \"perplexity/pplx-decider-latest\" }\nworkflow W() { delegate \"x\" to A -> r\n note \"{r}\" }\n";
        assertThat(check(agent, Map.of("PERPLEXITY_API_KEY", "x"), out)).isNotZero();
        assertThat(out.toString()).contains("Perplexity decision model").contains("does not chat");
    }

    @Test
    void theAuditListsItAsAHostedServiceAndItIsCalibrated() {
        LoomScript script = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        Finding f =
                SecurityAudit.audit(script, "w.loom").findings().stream()
                        .filter(x -> x.rule().equals("LA19"))
                        .findFirst()
                        .orElseThrow();
        assertThat(f.risk()).contains("Perplexity's Decisions API").contains("api.perplexity.ai");
        assertThat(f.risk()).doesNotContain("uncalibrated");
    }
}
