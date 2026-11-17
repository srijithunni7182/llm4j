package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 37c: {@code weave guide-mcp}, the one MCP server both hosts' Guide agents use. It answers
 * the handshake, lists only read, show and steering tools, forwards editor actions with the
 * session's token, refuses paths outside the project and malformed key names, records learnings
 * and checkpoints, and flags a builder's claim the journey does not support.
 */
class GuideMcpServerTest {
    @TempDir Path dir;
    @TempDir Path home;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);

    private Path project;
    private final List<String> sent = new ArrayList<>();
    private GuideMcpServer server;

    @BeforeEach
    void setUp() throws Exception {
        project = dir.resolve("p");
        assertThat(WeaveCLI.commandLine().execute("init", "pipeline", project.toString(), "--flat")).isZero();
        EditorChannel fake = (action, args) -> {
            sent.add(action + " " + args);
            return "shown";
        };
        server = server(fake);
    }

    private GuideMcpServer server(EditorChannel channel) {
        WeaveEnv env = WeaveEnv.system().withStreams(new PrintStream(new java.io.ByteArrayOutputStream()), new PrintStream(new java.io.ByteArrayOutputStream())).withEnvLookup(k -> null);
        return new GuideMcpServer(project.resolve("main.loom"), env, new GuideEvents(project, CLOCK), new GuideLearner(home, project, CLOCK), channel);
    }

    private JsonNode rpc(String method, Object params) throws Exception {
        ObjectNode m = JSON.createObjectNode();
        m.put("jsonrpc", "2.0");
        m.put("id", 1);
        m.put("method", method);
        m.set("params", JSON.valueToTree(params));
        return server.handle(JSON.writeValueAsString(m));
    }

    private JsonNode call(String tool, Map<String, Object> args) throws Exception {
        return rpc("tools/call", Map.of("name", tool, "arguments", args)).path("result");
    }

    private static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asText();
    }

    @Test
    void itAnswersTheHandshakeWithTheVersionTheClientAskedFor() throws Exception {
        JsonNode r = rpc("initialize", Map.of("protocolVersion", "2025-03-26")).path("result");

        assertThat(r.path("protocolVersion").asText()).isEqualTo("2025-03-26");
        assertThat(r.path("serverInfo").path("name").asText()).isEqualTo("llm4j-guide");
        assertThat(r.path("capabilities").has("tools")).isTrue();
        assertThat(r.path("instructions").asText()).contains("never edit files or spend money");
        assertThat(rpc("initialize", Map.of("protocolVersion", "1999-01-01")).path("result").path("protocolVersion").asText())
                .isEqualTo(GuideMcpServer.PROTOCOL_LATEST);
    }

    @Test
    void itOffersReadShowAndSteeringToolsOnlyAndNothingThatWritesOrRuns() throws Exception {
        JsonNode tools = rpc("tools/list", Map.of()).path("result").path("tools");

        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertThat(names).containsExactlyInAnyOrderElementsOf(GuideMcpServer.TOOLS.stream().map(GuideMcpServer.Tool::name).toList());
        Set<String> allowed = Set.of(
                "project_summary", "new_events", "facts", "explain_finding", "eval_summary", "learner",
                "record_learning", "journey_checkpoint", "draft_builder_instruction",
                "show_graph", "show_review", "show_eval_report", "show_file", "show_run", "start_observed_run",
                "open_secret_form", "open_walkthrough");
        assertThat(names).allMatch(allowed::contains);
        for (JsonNode t : tools) assertThat(t.path("annotations").path("destructiveHint").asBoolean()).isFalse();
    }

    @Test
    void unknownMethodsAndBadMessagesAreErrorsAndNotificationsGetNoAnswer() throws Exception {
        assertThat(rpc("resources/list", Map.of()).path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(server.handle("{not json").path("error").path("code").asInt()).isEqualTo(-32700);
        assertThat(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).isNull();
        assertThat(rpc("tools/call", Map.of("name", "write_file", "arguments", Map.of())).path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void theSummaryIsTheProjectSummary() throws Exception {
        JsonNode r = call("project_summary", Map.of());

        assertThat(r.path("isError").asBoolean()).isFalse();
        assertThat(JSON.readTree(text(r)).path("journey")).hasSize(11);
    }

    @Test
    void showToolsAreForwardedToTheEditor() throws Exception {
        assertThat(text(call("show_graph", Map.of("node", "Writer")))).isEqualTo("shown");
        assertThat(text(call("show_file", Map.of("path", "main.loom", "line", 3)))).isEqualTo("shown");

        assertThat(sent).hasSize(2);
        assertThat(sent.get(0)).startsWith("show_graph").contains("Writer");
    }

    @Test
    void aFileOutsideTheProjectIsRefusedBeforeTheEditorIsAsked() throws Exception {
        Path outside = dir.resolve("secret.txt");
        Files.writeString(outside, "x");
        Files.createSymbolicLink(project.resolve("link.txt"), outside);

        for (String p : List.of("../secret.txt", "/etc/passwd", "link.txt")) {
            JsonNode r = call("show_file", Map.of("path", p));
            assertThat(r.path("isError").asBoolean()).as(p).isTrue();
            assertThat(text(r)).as(p).contains("inside the project");
        }
        assertThat(sent).isEmpty();
    }

    @Test
    void aKeyFormNeedsAPlainNameAndAPlainHost() throws Exception {
        assertThat(call("open_secret_form", Map.of("name", "openai key", "host", "api.openai.com")).path("isError").asBoolean()).isTrue();
        assertThat(call("open_secret_form", Map.of("name", "OPENAI_API_KEY", "host", "https://api.openai.com/v1")).path("isError").asBoolean()).isTrue();
        assertThat(call("open_secret_form", Map.of("name", "OPENAI_API_KEY")).path("isError").asBoolean()).isTrue();
        assertThat(sent).isEmpty();

        assertThat(text(call("open_secret_form", Map.of("name", "OPENAI_API_KEY", "host", "api.openai.com")))).isEqualTo("shown");
    }

    @Test
    void learningIsRecordedForThePersonAndTheProject() throws Exception {
        assertThat(text(call("record_learning", Map.of("concept", "golden-dataset", "level", "understood")))).isEqualTo("Recorded.");
        assertThat(call("record_learning", Map.of("concept", "quantum", "level", "understood")).path("isError").asBoolean()).isTrue();

        JsonNode learner = JSON.readTree(text(call("learner", Map.of())));
        assertThat(learner.path("person").path("golden-dataset").path("level").asText()).isEqualTo("understood");
        assertThat(learner.path("thisProject").path("golden-dataset").path("level").asText()).isEqualTo("understood");
        assertThat(Files.isRegularFile(home.resolve(".loom/guide/learner.json"))).isTrue();
    }

    @Test
    void aBuildersClaimTheJourneyDoesNotSupportIsFlaggedToTheGuide() throws Exception {
        String answer = text(call("journey_checkpoint", Map.of("stage", "deploy", "did", "done", "why", "wrote the files")));

        assertThat(answer).contains("the journey says deploy is not-started");
        JsonNode events = JSON.readTree(text(call("new_events", Map.of()))).path("events");
        assertThat(events.findValuesAsText("kind")).containsExactly("checkpoint", "claim-mismatch");
        assertThat(JSON.readTree(text(call("new_events", Map.of()))).path("events")).isEmpty();
    }

    @Test
    void aTrueClaimIsJustRecorded() throws Exception {
        assertThat(text(call("journey_checkpoint", Map.of("stage", "script", "did", "done", "why", "checks clean")))).isEqualTo("Recorded.");
        assertThat(call("journey_checkpoint", Map.of("stage", "nowhere", "did", "done", "why", "x")).path("isError").asBoolean()).isTrue();
    }

    @Test
    void factsAndExplanationsComeFromTheCatalogueAndTheRules() throws Exception {
        assertThat(JSON.readTree(text(call("facts", Map.of("id", "budgets")))).path("facts").get(0).path("not").asText()).isNotBlank();
        assertThat(call("facts", Map.of("id", "warp")).path("isError").asBoolean()).isTrue();
        assertThat(call("explain_finding", Map.of("rule", "rm -rf")).path("isError").asBoolean()).isTrue();
        assertThat(text(call("eval_summary", Map.of()))).contains("evaluation");
    }

    @Test
    void itServesLineByLineAndWritesOnlyProtocolMessages() throws Exception {
        StringWriter out = new StringWriter();
        server.serve(new BufferedReader(new StringReader(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"
                        + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n\n"
                        + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n")), out);

        String[] lines = out.toString().strip().split("\n");
        assertThat(lines).hasSize(2);
        for (String l : lines) assertThat(JSON.readTree(l).path("jsonrpc").asText()).isEqualTo("2.0");
    }

    @Test
    void withNoEditorOpenTheShowToolsSaySoAndTheReadToolsStillWork() throws Exception {
        server = server(EditorChannel.mailbox(project, CLOCK, d -> {}));

        assertThat(text(call("show_graph", Map.of()))).isEqualTo(EditorChannel.NOT_OPEN);
        assertThat(call("project_summary", Map.of()).path("isError").asBoolean()).isFalse();
    }

    @Test
    void theMailboxHandsTheEditorAWholeRequestAndReadsItsAnswer() throws Exception {
        Path guide = Files.createDirectories(project.resolve(".loom/guide"));
        Files.writeString(guide.resolve("editor.json"), "{\"pid\":1}");
        // the fake editor answers each request it finds when the channel waits
        io.github.llm4j.ratelimit.Sleeper editor = d -> {
            try (var reqs = Files.list(guide.resolve("requests"))) {
                for (Path r : reqs.filter(f -> f.toString().endsWith(".json")).toList()) {
                    JsonNode req = JSON.readTree(r.toFile());
                    Files.createDirectories(guide.resolve("answers"));
                    Files.writeString(guide.resolve("answers").resolve(req.path("id").asText() + ".json"),
                            "{\"result\":\"opened " + req.path("action").asText() + " on " + req.path("args").path("node").asText() + "\"}");
                    Files.delete(r);
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        };
        EditorChannel ch = EditorChannel.mailbox(project, CLOCK, editor);

        assertThat(ch.send("show_graph", JSON.createObjectNode().put("node", "Writer"))).isEqualTo("opened show_graph on Writer");
        try (var left = Files.list(guide.resolve("answers"))) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void aRequestTheEditorNeverAnswersIsGivenUpAndRemoved() throws Exception {
        Path guide = Files.createDirectories(project.resolve(".loom/guide"));
        Files.writeString(guide.resolve("editor.json"), "{\"pid\":1}");
        Instant[] now = {Instant.parse("2026-11-17T10:00:00Z")};
        Clock moving = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return now[0]; }
        };
        EditorChannel ch = EditorChannel.mailbox(project, moving, d -> now[0] = now[0].plus(d));

        assertThat(ch.send("show_graph", JSON.createObjectNode())).contains("did not answer in time");
        try (var left = Files.list(guide.resolve("requests"))) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void theEventsFileKeepsTheNewestAndHoldsNoLongText() throws Exception {
        GuideEvents events = new GuideEvents(project, CLOCK);
        for (int i = 0; i < GuideEvents.MAX + 20; i++) events.append("saved", "", "main.loom", Map.of("n", String.valueOf(i)));
        events.append("saved", "", "x".repeat(5000), Map.of());

        List<String> lines = Files.readAllLines(events.file());
        assertThat(lines).hasSize(GuideEvents.MAX);
        assertThat(lines.get(lines.size() - 1).length()).isLessThan(400);
        assertThat(events.undiscussed(false)).hasSize(GuideEvents.MAX_PER_READ);
        assertThat(events.undiscussedCount()).isEqualTo(GuideEvents.MAX);
    }
}
