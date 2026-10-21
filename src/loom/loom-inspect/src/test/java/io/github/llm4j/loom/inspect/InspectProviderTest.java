package io.github.llm4j.loom.inspect;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.observe.Observe;
import io.github.llm4j.loom.observe.ObserveOptions;
import io.github.llm4j.loom.observe.ObserveProvider;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * weave's hook finds the add-on, and a real run shows up on the page's JSON. No model, no network
 * beyond loopback.
 */
class InspectProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCRIPT =
            """
            agent Researcher { model: "m" tools: [Lookup] }
            workflow Main() { delegate "find the answer" to Researcher -> out }
            """;

    private static HarnessExecutor executor(List<String> answers) {
        return executor(answers, SCRIPT);
    }

    private static HarnessExecutor executor(List<String> answers, String source) {
        int[] next = {0};
        LLMClientFactory factory =
                model ->
                        new LLMClient() {
                            @Override
                            public LLMResponse chat(LLMRequest request) {
                                return LLMResponse.builder()
                                        .content(answers.get(next[0]++))
                                        .model(model)
                                        .tokenUsage(10, 5, 15)
                                        .build();
                            }

                            @Override
                            public Stream<LLMResponse> chatStream(LLMRequest request) {
                                return Stream.of(chat(request));
                            }
                        };
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
                        return "test";
                    }

                    @Override
                    public String execute(Map<String, Object> args) {
                        return "the answer is 42";
                    }
                });
        return new HarnessExecutor(
                new LoomParser(new Lexer(source).tokenize()).parseScript(), tools, factory);
    }

    @Test
    void theServiceLoaderFindsTheAddOn() throws Exception {
        try (InspectSession session =
                (InspectSession)
                        Observe.open(
                                ObserveOptions.parse("0", null, null, "me", null),
                                new PrintStream(new ByteArrayOutputStream()))) {
            assertThat(session.describe())
                    .startsWith("http://127.0.0.1:")
                    .isEqualTo("http://127.0.0.1:" + session.port());
        }
    }

    @Test
    void aRealRunAppearsOnThePageAndFinishesWithTheRightTotals() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        try (InspectSession session =
                (InspectSession)
                        new InspectObserveProvider()
                                .open(
                                        ObserveOptions.parse("0", null, "full", "local", null),
                                        null)) {
            HarnessExecutor e =
                    executor(
                            List.of(
                                    "```json\n{\"thought\": \"use Lookup\", \"action\": \"Lookup\", \"action_input\": {\"q\": \"x\"}}\n```",
                                    "```json\n{\"thought\": \"t\", \"final_answer\": \"forty-two\"}\n```"));
            e.setRunId("r-page");
            session.attach(e.observable()); // before initialize, as weave does
            e.initialize();
            e.executeWorkflow("Main", Map.of());
            assertThat(session.awaitIdle(Duration.ofSeconds(3))).isTrue();

            String base = "http://127.0.0.1:" + session.port();
            JsonNode list =
                    JSON.readTree(
                            http.send(
                                            HttpRequest.newBuilder(URI.create(base + "/api/runs"))
                                                    .GET()
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofString())
                                    .body());
            assertThat(list.get("runs")).hasSize(1);
            String trace = list.get("runs").get(0).get("traceId").asText();
            JsonNode run =
                    JSON.readTree(
                            http.send(
                                            HttpRequest.newBuilder(
                                                            URI.create(base + "/api/runs/" + trace))
                                                    .GET()
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofString())
                                    .body());
            assertThat(run.get("status").asText()).isEqualTo("finished");
            assertThat(run.get("runId").asText()).isEqualTo("r-page");
            assertThat(run.get("workflow").asText()).isEqualTo("Main");
            assertThat(run.at("/totals/tokens").asLong()).isEqualTo(30L);
            assertThat(run.at("/totals/calls").asLong()).isEqualTo(2L);
            assertThat(run.at("/agents/0/name").asText()).isEqualTo("Researcher");
            assertThat(run.at("/agents/0/status").asText()).isEqualTo("done");
            assertThat(run.at("/tools/0/name").asText()).isEqualTo("Lookup");
            assertThat(run.at("/tools/0/calls").asLong()).isEqualTo(1L);
            assertThat(run.get("recent").toString()).contains("the answer is 42");
        }
    }

    @Test
    void theGraphReachesTheRunEvenWhenWeaveSetsTheRunIdAfterAttaching() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        try (InspectSession session =
                (InspectSession)
                        new InspectObserveProvider()
                                .open(
                                        ObserveOptions.parse("0", null, "full", "local", null),
                                        null)) {
            HarnessExecutor e =
                    executor(
                            List.of(
                                    "```json\n{\"thought\": \"t\", \"final_answer\": \"ok\"}\n```"));
            session.attach(e.observable());
            e.setRunId("r-late"); // weave does this after attach
            e.initialize();
            e.executeWorkflow("Main", Map.of());
            assertThat(session.awaitIdle(Duration.ofSeconds(3))).isTrue();
            String base = "http://127.0.0.1:" + session.port();
            String trace =
                    JSON.readTree(
                                    http.send(
                                                    HttpRequest.newBuilder(
                                                                    URI.create(base + "/api/runs"))
                                                            .GET()
                                                            .build(),
                                                    HttpResponse.BodyHandlers.ofString())
                                            .body())
                            .at("/runs/0/traceId")
                            .asText();
            JsonNode graph =
                    JSON.readTree(
                            http.send(
                                            HttpRequest.newBuilder(
                                                            URI.create(
                                                                    base
                                                                            + "/api/runs/"
                                                                            + trace
                                                                            + "/graph"))
                                                    .GET()
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofString())
                                    .body());
            assertThat(graph.at("/workflows/0/name").asText()).isEqualTo("Main");
            assertThat(graph.at("/workflows/0/nodes/0/path").asText()).isEqualTo("Main/s0");
        }
    }

    @Test
    void whenARunEndsItsSinkIsReleased_soADaemonFiringManyRunsDoesNotKeepAThreadForEach()
            throws Exception {
        try (InspectSession session =
                (InspectSession)
                        new InspectObserveProvider()
                                .open(
                                        ObserveOptions.parse("0", null, "full", "local", null),
                                        null)) {
            for (int i = 0; i < 5; i++) {
                HarnessExecutor e =
                        executor(
                                List.of(
                                        "```json\n{\"thought\": \"t\", \"final_answer\": \"ok\"}\n```"));
                e.setRunId("r-many-" + i);
                session.attach(e.observable());
                e.initialize();
                e.executeWorkflow("Main", Map.of());
            }
            long end = System.currentTimeMillis() + 3000;
            while (session.liveSinks() > 0 && System.currentTimeMillis() < end) Thread.sleep(5);
            assertThat(session.liveSinks()).isZero();
            assertThat(session.registry().summaries())
                    .hasSize(5)
                    .allSatisfy(r -> assertThat(r.get("status")).isEqualTo("finished"));
        }
    }

    private static final String JUDGE =
            """
            agent Lead { model: "m" }
            workflow Main() {
                loop until (review == "COMPLETE") max 4 {
                    delegate "is it finished? round {_loopRound}" to Lead -> review
                }
            }
            """;

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;

    /**
     * The script and, beside it, the rules that say how to watch it: the script itself says nothing
     * about watching.
     */
    private java.nio.file.Path judgeWithRules(String rules) throws Exception {
        java.nio.file.Path script =
                java.nio.file.Files.writeString(dir.resolve("judge.loom"), JUDGE);
        if (rules != null) java.nio.file.Files.writeString(dir.resolve("judge.inspect"), rules);
        return script;
    }

    private static String answer(String text) {
        return "```json\n{\"thought\": \"t\", \"final_answer\": \"" + text + "\"}\n```";
    }

    @Test
    @SuppressWarnings("unchecked")
    void aScriptsNoProgressRuleRaisesAnIncidentFromARealLoop_andOnlyAHashOfTheVariableIsRecorded()
            throws Exception {
        try (InspectSession session =
                (InspectSession)
                        new InspectObserveProvider()
                                .open(
                                        ObserveOptions.parse("0", null, "metadata", "local", null),
                                        null)) {
            java.nio.file.Path script =
                    judgeWithRules(
                            "# how to watch judge.loom\nLead {\n    no_progress: 2 rounds on review   # the verdict that should change\n    same_output: 9 times\n}\n");
            HarnessExecutor e =
                    executor(
                            List.of(
                                    answer("INCOMPLETE"),
                                    answer("INCOMPLETE"),
                                    answer("INCOMPLETE"),
                                    answer("INCOMPLETE")),
                            JUDGE);
            e.setRunId("r-judge");
            session.attach(e.observable(), script);
            e.initialize();
            e.executeWorkflow("Main", Map.of());
            assertThat(session.awaitIdle(Duration.ofSeconds(3))).isTrue();
            Map<String, Object> view =
                    session.registry().summaries().isEmpty()
                            ? Map.of()
                            : session.registry()
                                    .findByTrace(
                                            io.github.llm4j.loom.inspect.otel.Ids.traceId(
                                                    "r-judge"))
                                    .orElseThrow()
                                    .view();
            List<Map<String, Object>> incidents = (List<Map<String, Object>>) view.get("incidents");
            Map<String, Object> inc =
                    incidents.stream()
                            .filter(i -> String.valueOf(i.get("title")).contains("has not changed"))
                            .findFirst()
                            .orElseThrow();
            assertThat(inc).containsEntry("agent", "Lead");
            assertThat(String.valueOf(view))
                    .doesNotContain("INCOMPLETE"); // metadata level: no answer text on the page
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aVariableThatKeepsChangingIsProgress() throws Exception {
        try (InspectSession session =
                (InspectSession)
                        new InspectObserveProvider()
                                .open(
                                        ObserveOptions.parse("0", null, "full", "local", null),
                                        null)) {
            java.nio.file.Path script =
                    judgeWithRules("Lead { no_progress: 2 rounds on review }\n");
            HarnessExecutor e =
                    executor(
                            List.of(answer("A"), answer("B"), answer("C"), answer("COMPLETE")),
                            JUDGE);
            e.setRunId("r-progress");
            session.attach(e.observable(), script);
            e.initialize();
            e.executeWorkflow("Main", Map.of());
            assertThat(session.awaitIdle(Duration.ofSeconds(3))).isTrue();
            Map<String, Object> view =
                    session.registry()
                            .findByTrace(
                                    io.github.llm4j.loom.inspect.otel.Ids.traceId("r-progress"))
                            .orElseThrow()
                            .view();
            assertThat((List<Map<String, Object>>) view.get("incidents"))
                    .noneMatch(i -> String.valueOf(i.get("title")).contains("has not changed"));
        }
    }

    @Test
    void metadataDetailKeepsTextOffThePage() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        try (InspectSession session =
                (InspectSession)
                        new InspectObserveProvider()
                                .open(
                                        ObserveOptions.parse("0", null, "metadata", "local", null),
                                        null)) {
            HarnessExecutor e =
                    executor(
                            List.of(
                                    "```json\n{\"thought\": \"secret thought\", \"final_answer\": \"top secret answer\"}\n```"));
            e.setRunId("r-meta");
            session.attach(e.observable());
            e.initialize();
            e.executeWorkflow("Main", Map.of());
            assertThat(session.awaitIdle(Duration.ofSeconds(3))).isTrue();
            String body =
                    http.send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://127.0.0.1:"
                                                                    + session.port()
                                                                    + "/api/runs"))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .body();
            String trace = JSON.readTree(body).at("/runs/0/traceId").asText();
            String run =
                    http.send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://127.0.0.1:"
                                                                    + session.port()
                                                                    + "/api/runs/"
                                                                    + trace))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .body();
            assertThat(run)
                    .doesNotContain("secret thought")
                    .doesNotContain("top secret answer")
                    .contains("\"totals\"");
        }
    }

    @Test
    void theProviderIsNamed() {
        ObserveProvider p = new InspectObserveProvider();
        assertThat(p.name()).isEqualTo("loom-inspect");
    }

    @Test
    void aRulesFileGivenExplicitlyIsUsedWhereverItIs_andNoFileMeansTheDefaults() throws Exception {
        java.nio.file.Path elsewhere =
                java.nio.file.Files.writeString(
                        dir.resolve("prod-rules.txt"),
                        "Lead { no_progress: 2 rounds on review }\n");
        try (InspectSession session =
                (InspectSession)
                        new InspectObserveProvider()
                                .open(
                                        ObserveOptions.parse("0", null, "metadata", "local", null)
                                                .withRules(elsewhere),
                                        null)) {
            HarnessExecutor e =
                    executor(List.of(answer("X"), answer("X"), answer("X"), answer("X")), JUDGE);
            e.setRunId("r-explicit");
            session.attach(
                    e.observable(),
                    judgeWithRules(
                            null)); // no judge.inspect beside the script: the explicit file wins
            // anyway
            e.initialize();
            e.executeWorkflow("Main", Map.of());
            assertThat(session.awaitIdle(Duration.ofSeconds(3))).isTrue();
            assertThat(
                            String.valueOf(
                                    session.registry()
                                            .findByTrace(
                                                    io.github.llm4j.loom.inspect.otel.Ids.traceId(
                                                            "r-explicit"))
                                            .orElseThrow()
                                            .view()
                                            .get("incidents")))
                    .contains("has not changed");
        }
        try (InspectSession session =
                (InspectSession)
                        new InspectObserveProvider()
                                .open(
                                        ObserveOptions.parse("0", null, "metadata", "local", null),
                                        null)) {
            HarnessExecutor e =
                    executor(List.of(answer("X"), answer("X"), answer("X"), answer("X")), JUDGE);
            e.setRunId("r-defaults");
            session.attach(e.observable(), judgeWithRules(null));
            e.initialize();
            e.executeWorkflow("Main", Map.of());
            assertThat(session.awaitIdle(Duration.ofSeconds(3))).isTrue();
            assertThat(
                            String.valueOf(
                                    session.registry()
                                            .findByTrace(
                                                    io.github.llm4j.loom.inspect.otel.Ids.traceId(
                                                            "r-defaults"))
                                            .orElseThrow()
                                            .view()
                                            .get("incidents")))
                    .doesNotContain("has not changed");
        }
    }

    @Test
    void aRulesFileWithErrorsIsReportedOnceAndNotUsed_theRunIsNotAffected() throws Exception {
        java.io.ByteArrayOutputStream console = new java.io.ByteArrayOutputStream();
        java.nio.file.Path script =
                judgeWithRules(
                        "Leed { same_action: 2 times }\nLead { no_progress: 2 rounds on reveiw }\n");
        try (InspectSession session =
                (InspectSession)
                        new InspectObserveProvider()
                                .open(
                                        ObserveOptions.parse("0", null, "full", "local", null),
                                        new java.io.PrintStream(console))) {
            HarnessExecutor e = executor(List.of(answer("COMPLETE")), JUDGE);
            e.setRunId("r-bad-rules");
            session.attach(e.observable(), script);
            e.initialize();
            e.executeWorkflow("Main", Map.of());
            assertThat(session.awaitIdle(Duration.ofSeconds(3))).isTrue();
            assertThat(
                            session.registry()
                                    .findByTrace(
                                            io.github.llm4j.loom.inspect.otel.Ids.traceId(
                                                    "r-bad-rules"))
                                    .orElseThrow()
                                    .view()
                                    .get("status"))
                    .isEqualTo("finished");
        }
        assertThat(console.toString())
                .contains("no agent named Leed")
                .contains("Lead")
                .contains("no workflow variable named reveiw")
                .contains("not using judge.inspect");
    }

    @Test
    void theRulesCheckCommandReportsProblemsAndCountsErrors() throws Exception {
        java.nio.file.Path script = judgeWithRules(null);
        io.github.llm4j.loom.ast.LoomScript loaded =
                new LoomParser(new Lexer(JUDGE).tokenize()).parseScript();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.nio.file.Path good =
                java.nio.file.Files.writeString(
                        dir.resolve("good.inspect"), "Lead { same_output: 3 times }\n");
        assertThat(
                        new InspectObserveProvider()
                                .checkRules(loaded, good, new java.io.PrintStream(out)))
                .isZero();
        assertThat(out.toString()).contains("all fine");
        java.nio.file.Path bad =
                java.nio.file.Files.writeString(
                        dir.resolve("bad.inspect"), "Nobody { same_output: 3 times }\n");
        out.reset();
        assertThat(
                        new InspectObserveProvider()
                                .checkRules(loaded, bad, new java.io.PrintStream(out)))
                .isEqualTo(1);
        assertThat(out.toString()).contains("no agent named Nobody");
        assertThat(script).exists();
    }

    private static final String SLOW =
            """
            workflow Main() {
                note "before"
                sleep 5s
                note "after"
                note "later"
            }
            """;

    private String post(HttpClient http, String url, String body, String cookie, String csrf)
            throws Exception {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) b.header("Cookie", cookie);
        if (csrf != null) b.header("X-CSRF-Token", csrf);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode()
                + " "
                + r.body()
                + (r.headers()
                        .firstValue("Set-Cookie")
                        .map(c -> " COOKIE=" + c.split(";")[0])
                        .orElse(""));
    }

    @Test
    void
            aStopFromThePageEndsARealRunAtItsNextStep_afterASignInWithThePasswordFromTheAppsSecretStore()
                    throws Exception {
        java.nio.file.Path audit = dir.resolve("audit.log");
        System.setProperty("loom.inspect.audit", audit.toString());
        try (InspectSession session =
                (InspectSession)
                        new InspectObserveProvider()
                                .open(
                                        ObserveOptions.parse("0", null, "metadata", "local", null),
                                        null)) {
            HarnessExecutor e = executor(List.of(), SLOW);
            io.github.llm4j.secret.InMemorySecretStore secrets =
                    new io.github.llm4j.secret.InMemorySecretStore();
            secrets.put("LOOM_INSPECT_ADMIN_PASSWORD", "correct horse battery");
            e.setSecretStore(secrets);
            java.util.concurrent.CountDownLatch release =
                    new java.util.concurrent.CountDownLatch(1);
            e.setSleeper(d -> release.await()); // the run waits here, in the middle of the sleep
            // step, until the test says so
            e.setRunId("r-stop");
            session.attach(e.observable());
            e.initialize();
            java.util.concurrent.atomic.AtomicReference<Throwable> ended =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread run =
                    new Thread(
                            () -> {
                                try {
                                    e.executeWorkflow("Main", Map.of());
                                } catch (Throwable t) {
                                    ended.set(t);
                                }
                            });
            run.start();
            String trace = io.github.llm4j.loom.inspect.otel.Ids.traceId("r-stop");
            String base = "http://127.0.0.1:" + session.port();
            HttpClient http = HttpClient.newHttpClient();
            long until = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < until
                    && session.registry().findByTrace(trace).isEmpty()) Thread.sleep(10);
            Thread.sleep(100);
            String login =
                    post(
                            http,
                            base + "/api/login",
                            "{\"password\":\"correct horse battery\"}",
                            null,
                            null);
            assertThat(login).startsWith("200");
            String cookie = login.substring(login.indexOf("COOKIE=") + 7);
            String csrf =
                    JSON.readTree(login.substring(4, login.indexOf(" COOKIE=")))
                            .get("csrf")
                            .asText();

            JsonNode view =
                    JSON.readTree(
                            http.send(
                                            HttpRequest.newBuilder(
                                                            URI.create(base + "/api/runs/" + trace))
                                                    .header("Cookie", cookie)
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofString())
                                    .body());
            assertThat(view.at("/controls/allowed").toString()).contains("stop").contains("pause");
            assertThat(post(http, base + "/api/runs/" + trace + "/stop", "{}", cookie, csrf))
                    .startsWith("202");
            release.countDown();
            run.join(5000);
            assertThat(run.isAlive()).isFalse();
            assertThat(ended.get()).isInstanceOf(io.github.llm4j.loom.runtime.RunStopped.class);
            assertThat(session.awaitIdle(Duration.ofSeconds(3))).isTrue();
            JsonNode after =
                    JSON.readTree(
                            http.send(
                                            HttpRequest.newBuilder(
                                                            URI.create(base + "/api/runs/" + trace))
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofString())
                                    .body());
            assertThat(after.get("status").asText()).isEqualTo("stopped");
            assertThat(after.at("/control/pending").isMissingNode()).isTrue();
            assertThat(java.nio.file.Files.readString(audit))
                    .contains("\"action\":\"stop\"")
                    .contains("accepted")
                    .doesNotContain("correct horse");
        } finally {
            System.clearProperty("loom.inspect.audit");
        }
    }

    @Test
    void withoutAnAdminPasswordInTheStoreThePageOffersNoControlsAndSaysHowToGetThem()
            throws Exception {
        java.io.ByteArrayOutputStream console = new java.io.ByteArrayOutputStream();
        try (InspectSession session =
                (InspectSession)
                        new InspectObserveProvider()
                                .open(
                                        ObserveOptions.parse("0", null, "metadata", "local", null),
                                        new java.io.PrintStream(console))) {
            HarnessExecutor e = executor(List.of(), SLOW);
            e.setSleeper(d -> {});
            e.setRunId("r-nopw");
            session.attach(e.observable());
            e.initialize();
            e.executeWorkflow("Main", Map.of());
            assertThat(session.awaitIdle(Duration.ofSeconds(3))).isTrue();
            HttpClient http = HttpClient.newHttpClient();
            JsonNode s =
                    JSON.readTree(
                            http.send(
                                            HttpRequest.newBuilder(
                                                            URI.create(
                                                                    "http://127.0.0.1:"
                                                                            + session.port()
                                                                            + "/api/session"))
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofString())
                                    .body());
            assertThat(s.get("controlsEnabled").asBoolean()).isFalse();
            assertThat(s.get("controlsReason").asText()).contains("LOOM_INSPECT_ADMIN_PASSWORD");
            assertThat(
                            post(
                                    http,
                                    "http://127.0.0.1:" + session.port() + "/api/login",
                                    "{\"password\":\"guess-guess-guess\"}",
                                    null,
                                    null))
                    .startsWith("503");
        }
        assertThat(console.toString()).contains("pause, suspend and stop are off");
    }
}
