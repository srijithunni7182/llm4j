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

/** weave's hook finds the add-on, and a real run shows up on the page's JSON. No model, no network beyond loopback. */
class InspectProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCRIPT = """
            agent Researcher { model: "m" tools: [Lookup] }
            workflow Main() { delegate "find the answer" to Researcher -> out }
            """;

    private static HarnessExecutor executor(List<String> answers) {
        int[] next = {0};
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
        return new HarnessExecutor(new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript(), tools, factory);
    }

    @Test
    void theServiceLoaderFindsTheAddOn() throws Exception {
        try (InspectSession session = (InspectSession) Observe.open(ObserveOptions.parse("0", null, null, "me", null), new PrintStream(new ByteArrayOutputStream()))) {
            assertThat(session.describe()).startsWith("http://127.0.0.1:").isEqualTo("http://127.0.0.1:" + session.port());
        }
    }

    @Test
    void aRealRunAppearsOnThePageAndFinishesWithTheRightTotals() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        try (InspectSession session = (InspectSession) new InspectObserveProvider().open(ObserveOptions.parse("0", null, "full", "local", null), null)) {
            HarnessExecutor e = executor(List.of(
                    "```json\n{\"thought\": \"use Lookup\", \"action\": \"Lookup\", \"action_input\": {\"q\": \"x\"}}\n```",
                    "```json\n{\"thought\": \"t\", \"final_answer\": \"forty-two\"}\n```"));
            e.setRunId("r-page");
            session.attach(e.observable()); // before initialize, as weave does
            e.initialize();
            e.executeWorkflow("Main", Map.of());
            assertThat(session.awaitIdle(Duration.ofSeconds(3))).isTrue();

            String base = "http://127.0.0.1:" + session.port();
            JsonNode list = JSON.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/api/runs")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            assertThat(list.get("runs")).hasSize(1);
            String trace = list.get("runs").get(0).get("traceId").asText();
            JsonNode run = JSON.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/api/runs/" + trace)).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
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
    void metadataDetailKeepsTextOffThePage() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        try (InspectSession session = (InspectSession) new InspectObserveProvider().open(ObserveOptions.parse("0", null, "metadata", "local", null), null)) {
            HarnessExecutor e = executor(List.of("```json\n{\"thought\": \"secret thought\", \"final_answer\": \"top secret answer\"}\n```"));
            e.setRunId("r-meta");
            session.attach(e.observable());
            e.initialize();
            e.executeWorkflow("Main", Map.of());
            assertThat(session.awaitIdle(Duration.ofSeconds(3))).isTrue();
            String body = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + session.port() + "/api/runs")).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            String trace = JSON.readTree(body).at("/runs/0/traceId").asText();
            String run = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + session.port() + "/api/runs/" + trace)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            assertThat(run).doesNotContain("secret thought").doesNotContain("top secret answer").contains("\"totals\"");
        }
    }

    @Test
    void theProviderIsNamed() {
        ObserveProvider p = new InspectObserveProvider();
        assertThat(p.name()).isEqualTo("loom-inspect");
    }
}
