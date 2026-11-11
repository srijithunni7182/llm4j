package io.github.llm4j.a2a.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One test per row of {@link SpecRules}: the mock must enforce what it says it enforces. */
class MockA2AAgentTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> COVERED = new HashSet<>();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<String, String> V1 = Map.of("A2A-Version", "1.0");

    private MockA2AAgent agent;
    private int nextId;

    @BeforeEach
    void start() {
        agent = MockA2AAgent.start("https://agent.example.test");
    }

    @AfterEach
    void stop() {
        assertThat(agent.selfCheckFailures()).as("the mock's own output must be valid").isEmpty();
        agent.close();
    }

    @AfterAll
    static void everyRuleHasATest() {
        Set<String> all = SpecRules.ALL.stream().map(SpecRules.Rule::id).collect(Collectors.toSet());
        assertThat(COVERED).as("rules with no test").containsAll(all);
    }

    private static void covers(String id) {
        SpecRules.byId(id);
        COVERED.add(id);
    }

    private JsonNode rpc(String method, String params, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(agent.loopbackBase() + "/rpc")).header("Content-Type", "application/json");
        headers.forEach(builder::header);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + (++nextId) + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
        var response = HTTP.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(response.body());
    }

    private JsonNode rpc(String method, String params) throws Exception {
        return rpc(method, params, V1);
    }

    private static String message(String id, String extra, String text) {
        return "{\"messageId\":\"" + id + "\",\"role\":\"ROLE_USER\"" + extra + ",\"parts\":[{\"text\":\"" + text + "\"}]}";
    }

    private static String send(String message, boolean immediately) {
        return "{\"message\":" + message + ",\"configuration\":{\"returnImmediately\":" + immediately + "}}";
    }

    private static int code(JsonNode response) {
        return response.path("error").path("code").asInt(0);
    }

    @Test
    void theCardIsValidHasCachingHeadersAndAdvertisesNoStreaming() throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(agent.loopbackBase() + "/.well-known/agent-card.json")).build(), HttpResponse.BodyHandlers.ofString());
        OfficialDefinition.validate("AgentCard", response.body());
        assertThat(response.headers().firstValue("ETag")).isPresent();
        assertThat(response.headers().firstValue("Cache-Control")).isPresent();
        assertThat(JSON.readTree(response.body()).at("/capabilities/streaming").asBoolean()).isFalse();
    }

    @Test
    void messageNamingATaskMustNameAnExistingOne() throws Exception {
        covers("task-id-exists");
        var r = rpc("SendMessage", send(message("m1", ",\"taskId\":\"nope\"", "hi"), true));
        assertThat(code(r)).isEqualTo(-32001);
    }

    @Test
    void taskIdsAreMadeByTheServerAndAreUnique() throws Exception {
        covers("task-ids-are-server-made");
        var a = rpc("SendMessage", send(message("m1", "", "hi"), true)).at("/result/task/id").asText();
        var b = rpc("SendMessage", send(message("m2", "", "hi"), true)).at("/result/task/id").asText();
        assertThat(a).isNotEmpty().isNotEqualTo(b);
    }

    @Test
    void contextIsInferredFromTheTaskAndAMismatchIsRejected() throws Exception {
        covers("context-inferred");
        covers("context-mismatch-rejected");
        agent.script(MockA2AAgent.Script.of(MockA2AAgent.Step.asks("q?"), MockA2AAgent.Step.completedWithText("ok")));
        var first = rpc("SendMessage", send(message("m1", "", "hi"), true)).at("/result/task");
        var taskId = first.get("id").asText();
        var mismatch = rpc("SendMessage", send(message("m2", ",\"taskId\":\"" + taskId + "\",\"contextId\":\"other\"", "x"), true));
        assertThat(code(mismatch)).isEqualTo(-32602);
        var inferred = rpc("SendMessage", send(message("m3", ",\"taskId\":\"" + taskId + "\"", "answer"), true));
        assertThat(inferred.at("/result/task/contextId").asText()).isEqualTo(first.get("contextId").asText());
    }

    @Test
    void aProposedContextIsAcceptedOrRejectedAndNeverReplaced() throws Exception {
        covers("context-never-replaced");
        covers("generated-context-returned");
        var accepted = rpc("SendMessage", send(message("m1", ",\"contextId\":\"mine\"", "hi"), true));
        assertThat(accepted.at("/result/task/contextId").asText()).isEqualTo("mine");
        agent.acceptsProposedContext(false);
        assertThat(code(rpc("SendMessage", send(message("m2", ",\"contextId\":\"mine2\"", "hi"), true)))).isEqualTo(-32602);
        assertThat(rpc("SendMessage", send(message("m3", "", "hi"), true)).at("/result/task/contextId").asText()).isNotEmpty();
    }

    @Test
    void aFinishedTaskTakesNoMoreMessages() throws Exception {
        covers("terminal-takes-no-messages");
        var task = rpc("SendMessage", send(message("m1", "", "hi"), true)).at("/result/task/id").asText();
        assertThat(code(rpc("SendMessage", send(message("m2", ",\"taskId\":\"" + task + "\"", "more"), true)))).isEqualTo(-32004);
    }

    @Test
    void aSendIsBlockingUnlessItAsksToReturnImmediately() throws Exception {
        covers("blocking-by-default");
        covers("return-immediately");
        agent.script(MockA2AAgent.Script.of(MockA2AAgent.Step.working(), MockA2AAgent.Step.working(), MockA2AAgent.Step.completedWithText("ok")));
        var immediate = rpc("SendMessage", send(message("m1", "", "hi"), true));
        assertThat(immediate.at("/result/task/status/state").asText()).isEqualTo("TASK_STATE_WORKING");
        var blocking = rpc("SendMessage", "{\"message\":" + message("m2", "", "hi") + "}");
        assertThat(blocking.at("/result/task/status/state").asText()).isEqualTo("TASK_STATE_COMPLETED");
    }

    @Test
    void listIsNewestFirstPagedWithATokenAlwaysPresent() throws Exception {
        covers("list-sorted-newest-first");
        covers("next-page-token-always");
        covers("artifacts-omitted-unless-asked");
        for (int i = 0; i < 3; i++) {
            rpc("SendMessage", send(message("m" + i, "", "hi"), true));
        }
        var page1 = rpc("ListTasks", "{\"pageSize\":2}").get("result");
        assertThat(page1.get("tasks").size()).isEqualTo(2);
        assertThat(page1.get("nextPageToken").asText()).isNotEmpty();
        assertThat(page1.at("/tasks/0/id").asText()).isEqualTo("task-3");
        assertThat(page1.at("/tasks/0/artifacts").isMissingNode()).isTrue();
        var page2 = rpc("ListTasks", "{\"pageSize\":2,\"pageToken\":\"" + page1.get("nextPageToken").asText() + "\",\"includeArtifacts\":true}").get("result");
        assertThat(page2.get("nextPageToken").asText()).isEmpty();
        assertThat(page2.has("nextPageToken")).isTrue();
        assertThat(page2.at("/tasks/0/artifacts").isArray()).isTrue();
        assertThat(code(rpc("ListTasks", "{\"pageSize\":101}"))).isEqualTo(-32602);
        assertThat(code(rpc("ListTasks", "{\"pageSize\":0}"))).isEqualTo(-32602);
    }

    @Test
    void tasksBelongToTheCallerThatMadeThem() throws Exception {
        covers("list-scoped-to-caller");
        covers("get-scoped-to-caller");
        var alice = Map.of("A2A-Version", "1.0", "Authorization", "alice");
        var bob = Map.of("A2A-Version", "1.0", "Authorization", "bob");
        var task = rpc("SendMessage", send(message("m1", "", "hi"), true), alice).at("/result/task/id").asText();
        assertThat(rpc("ListTasks", "{}", bob).at("/result/tasks").size()).isZero();
        assertThat(rpc("ListTasks", "{}", alice).at("/result/tasks").size()).isEqualTo(1);
        assertThat(code(rpc("GetTask", "{\"id\":\"" + task + "\"}", bob))).isEqualTo(-32001);
        assertThat(rpc("GetTask", "{\"id\":\"" + task + "\"}", alice).at("/result/id").asText()).isEqualTo(task);
    }

    @Test
    void cancelIsIdempotentAndRefusedForAFinishedTask() throws Exception {
        covers("cancel-idempotent");
        covers("cancel-terminal-not-cancelable");
        agent.script(MockA2AAgent.Script.of(MockA2AAgent.Step.working(), MockA2AAgent.Step.completedWithText("ok")));
        var running = rpc("SendMessage", send(message("m1", "", "hi"), true)).at("/result/task/id").asText();
        assertThat(rpc("CancelTask", "{\"id\":\"" + running + "\"}").at("/result/status/state").asText()).isEqualTo("TASK_STATE_CANCELED");
        assertThat(rpc("CancelTask", "{\"id\":\"" + running + "\"}").at("/result/status/state").asText()).isEqualTo("TASK_STATE_CANCELED");
        agent.script(MockA2AAgent.Script.completesAtOnce("ok"));
        var done = rpc("SendMessage", send(message("m2", "", "hi"), true)).at("/result/task/id").asText();
        assertThat(code(rpc("CancelTask", "{\"id\":\"" + done + "\"}"))).isEqualTo(-32002);
    }

    @Test
    void theVersionHeaderIsRequiredAndAnEmptyOneMeansOldVersion() throws Exception {
        covers("version-header-required");
        assertThat(code(rpc("GetTask", "{\"id\":\"x\"}", Map.of()))).isEqualTo(-32009);
        assertThat(code(rpc("GetTask", "{\"id\":\"x\"}", Map.of("A2A-Version", "0.3")))).isEqualTo(-32009);
        assertThat(code(rpc("GetTask", "{\"id\":\"x\"}", Map.of("A2A-Version", "1.0")))).isEqualTo(-32001);
    }

    @Test
    void theVersionIsMatchedOnMajorAndMinorSoAPatchIsTheSameVersion() throws Exception {
        covers("version-header-required");
        assertThat(code(rpc("GetTask", "{\"id\":\"x\"}", Map.of("A2A-Version", "1.0.1")))).isEqualTo(-32001);
        assertThat(code(rpc("GetTask", "{\"id\":\"x\"}", Map.of("A2A-Version", "1.1")))).isEqualTo(-32009);
        assertThat(code(rpc("GetTask", "{\"id\":\"x\"}", Map.of("A2A-Version", "1.05")))).isEqualTo(-32009);
        assertThat(code(rpc("GetTask", "{\"id\":\"x\"}", Map.of("A2A-Version", "99.0")))).isEqualTo(-32009);
    }

    @Test
    void aRequiredExtensionMustBeDeclared() throws Exception {
        covers("required-extension");
        agent.requiredExtension("https://e.example.com/geo/v1");
        assertThat(code(rpc("GetTask", "{\"id\":\"x\"}"))).isEqualTo(-32008);
        assertThat(code(rpc("GetTask", "{\"id\":\"x\"}", Map.of("A2A-Version", "1.0", "A2A-Extensions", "https://e.example.com/geo/v1")))).isEqualTo(-32001);
    }

    @Test
    void streamingAndPushAreUnsupportedBecauseTheCardSaysSo() throws Exception {
        covers("streaming-unsupported");
        covers("push-unsupported");
        covers("extended-card-unsupported");
        assertThat(code(rpc("SendStreamingMessage", send(message("m1", "", "hi"), true)))).isEqualTo(-32004);
        assertThat(code(rpc("SubscribeToTask", "{\"id\":\"x\"}"))).isEqualTo(-32004);
        // its card says extendedAgentCard is false, so the answer is UnsupportedOperation (spec 3.3.4); -32007 is for a card that says true and has none
        assertThat(code(rpc("GetExtendedAgentCard", "{}"))).isEqualTo(-32004);
        assertThat(code(rpc("CreateTaskPushNotificationConfig", "{\"taskId\":\"x\",\"url\":\"https://w.example.com\"}"))).isEqualTo(-32003);
        assertThat(code(rpc("Nope", "{}"))).isEqualTo(-32601);
    }

    @Test
    void anA2AErrorCarriesErrorInfo() throws Exception {
        covers("error-info");
        var data = rpc("GetTask", "{\"id\":\"x\"}").at("/error/data/0");
        assertThat(data.get("@type").asText()).isEqualTo("type.googleapis.com/google.rpc.ErrorInfo");
        assertThat(data.get("reason").asText()).isEqualTo("TASK_NOT_FOUND");
        assertThat(data.get("domain").asText()).isEqualTo("a2a-protocol.org");
    }

    @Test
    void aRequestMissingARequiredFieldOrWithSnakeCaseIsRefused() throws Exception {
        covers("required-fields");
        assertThat(code(rpc("SendMessage", "{\"message\":{\"messageId\":\"m\",\"role\":\"ROLE_USER\"}}"))).isEqualTo(-32602);
        assertThat(code(rpc("GetTask", "{\"task_id\":\"x\"}"))).isEqualTo(-32602);
        assertThat(code(rpc("SendMessage", send("{\"messageId\":\"m\",\"role\":\"user\",\"parts\":[{\"text\":\"x\"}]}", true)))).isEqualTo(-32602);
    }

    @Test
    void aRepeatedMessageIdIsDeduplicatedOnlyWhenSwitchedOn() throws Exception {
        covers("send-may-dedupe");
        rpc("SendMessage", send(message("same", "", "hi"), true));
        rpc("SendMessage", send(message("same", "", "hi"), true));
        assertThat(agent.taskCount()).isEqualTo(2);
        agent.dedupeOnMessageId(true);
        rpc("SendMessage", send(message("again", "", "hi"), true));
        rpc("SendMessage", send(message("again", "", "hi"), true));
        assertThat(agent.taskCount()).isEqualTo(3);
        assertThat(agent.receivedMessageIds()).contains("same", "again");
    }

    @Test
    void anAuthorizationRequestComesWithAnExplanationOnATask() throws Exception {
        covers("auth-required-explains");
        agent.script(MockA2AAgent.Script.of(MockA2AAgent.Step.authRequired("Sign in to the portal")));
        var task = rpc("SendMessage", send(message("m1", "", "hi"), true)).at("/result/task");
        assertThat(task.at("/status/state").asText()).isEqualTo("TASK_STATE_AUTH_REQUIRED");
        assertThat(task.at("/status/message/parts/0/text").asText()).isEqualTo("Sign in to the portal");
    }

    @Test
    void aPlainMessageReplyMakesNoTask() throws Exception {
        agent.script(MockA2AAgent.Script.plainMessage("42 each"));
        var r = rpc("SendMessage", send(message("m1", "", "hi"), true));
        assertThat(r.at("/result/message/parts/0/text").asText()).isEqualTo("42 each");
        assertThat(agent.taskCount()).isZero();
    }

    @Test
    void historyIsReturnedUpToWhatIsAskedAndCutByTheServerLimit() throws Exception {
        agent.script(MockA2AAgent.Script.of(MockA2AAgent.Step.asks("q?"), MockA2AAgent.Step.completedWithText("ok")));
        var task = rpc("SendMessage", send(message("m1", "", "hi"), true)).at("/result/task/id").asText();
        rpc("SendMessage", send(message("m2", ",\"taskId\":\"" + task + "\"", "ans"), true));
        assertThat(rpc("GetTask", "{\"id\":\"" + task + "\",\"historyLength\":10}").at("/result/history").size()).isGreaterThanOrEqualTo(2);
        agent.historyLimit(1);
        assertThat(rpc("GetTask", "{\"id\":\"" + task + "\",\"historyLength\":10}").at("/result/history").size()).isEqualTo(1);
    }

    @Test
    void misbehavingModesDoWhatTheyAdvertise() throws Exception {
        agent.mode(MockA2AAgent.Mode.UNKNOWN_STATE);
        assertThat(rpc("SendMessage", send(message("m1", "", "hi"), true)).at("/result/task/status/state").asText()).isEqualTo("TASK_STATE_QUANTUM");
        agent.mode(MockA2AAgent.Mode.MALFORMED_JSON);
        var raw = HTTP.send(HttpRequest.newBuilder(URI.create(agent.loopbackBase() + "/rpc")).header("A2A-Version", "1.0")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"GetTask\",\"params\":{\"id\":\"x\"}}")).build(), HttpResponse.BodyHandlers.ofString());
        try {
            JSON.readTree(raw.body());
            org.junit.jupiter.api.Assertions.fail("expected malformed JSON");
        } catch (IOException expected) {
            // the response is not JSON, as asked
        }
        agent.mode(MockA2AAgent.Mode.CARD_ELSEWHERE);
        var card = HTTP.send(HttpRequest.newBuilder(URI.create(agent.loopbackBase() + "/.well-known/agent-card.json")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(card.body()).contains("evil.example.net");
    }

    @Test
    void everyRuleIdIsUniqueAndHasASection() {
        List<String> ids = SpecRules.ALL.stream().map(SpecRules.Rule::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(SpecRules.ALL).allMatch(r -> r.section().matches("\\d+(\\.\\d+)*"));
    }
}
