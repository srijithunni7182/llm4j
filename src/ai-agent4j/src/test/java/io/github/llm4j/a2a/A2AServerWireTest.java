package io.github.llm4j.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.a2a.testkit.OfficialDefinition;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The server half of the 1.0 helper: requests in, responses, errors and cards out, all valid
 * against the official definition.
 */
class A2AServerWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final A2ARequest SEND =
            new A2ARequest.Send(
                    A2AMessage.user("m", List.of(new Part.Text("x"))), true, null, null);
    private static final A2ARequest LIST =
            new A2ARequest.ListTasks(null, null, null, null, null, null, false);

    private static JsonNode id(int n) {
        return JSON.getNodeFactory().numberNode(n);
    }

    private static JsonNode result(String json) throws Exception {
        return JSON.readTree(json).get("result");
    }

    // ---- requests ----

    @Test
    void aSendRequestTheHelperBuiltIsParsedBackIntoTheModel() {
        var message =
                new A2AMessage(
                        "m-1",
                        A2AMessage.Role.USER,
                        List.of(new Part.Text("hi")),
                        "c-1",
                        null,
                        List.of());
        var json =
                A2AJsonRpc10.sendMessage(7, message, new SendOptions(true, List.of(), 3), "acme")
                        .body();
        var call = A2AJsonRpc10.parseRequest(json);
        var send = (A2ARequest.Send) call.request();
        assertThat(call.id().asInt()).isEqualTo(7);
        assertThat(send.message()).isEqualTo(message);
        assertThat(send.returnImmediately()).isTrue();
        assertThat(send.historyLength()).isEqualTo(3);
        assertThat(send.tenant()).isEqualTo("acme");
    }

    @Test
    void aSendWithNoConfigurationMeansTheCallerWantsToWait() {
        var call =
                A2AJsonRpc10.parseRequest(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SendMessage\",\"params\":{\"message\":{\"messageId\":\"m\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"x\"}]}}}");
        assertThat(((A2ARequest.Send) call.request()).returnImmediately()).isFalse();
    }

    @Test
    void getCancelAndListRequestsAreParsed() {
        assertThat(
                        A2AJsonRpc10.parseRequest(A2AJsonRpc10.getTask(1, "t-1", 4, null).body())
                                .request())
                .isEqualTo(new A2ARequest.GetTask("t-1", 4));
        assertThat(
                        A2AJsonRpc10.parseRequest(A2AJsonRpc10.cancelTask(2, "t-1", null).body())
                                .request())
                .isEqualTo(new A2ARequest.CancelTask("t-1"));
        var list =
                (A2ARequest.ListTasks)
                        A2AJsonRpc10.parseRequest(
                                        A2AJsonRpc10.listTasks(
                                                        3,
                                                        Instant.parse("2026-11-07T10:00:00Z"),
                                                        5,
                                                        20,
                                                        null)
                                                .body())
                                .request();
        assertThat(list.pageSize()).isEqualTo(20);
        assertThat(list.statusTimestampAfter()).isEqualTo(Instant.parse("2026-11-07T10:00:00Z"));
    }

    @Test
    void badRequestsAreClearErrors() {
        assertThatThrownBy(() -> A2AJsonRpc10.parseRequest("nope"))
                .isInstanceOf(A2AException.Malformed.class);
        assertThatThrownBy(() -> A2AJsonRpc10.parseRequest("{\"id\":1}"))
                .isInstanceOf(A2AException.Malformed.class);
        assertThatThrownBy(
                        () ->
                                A2AJsonRpc10.parseRequest(
                                        "{\"id\":1,\"method\":\"Teleport\",\"params\":{}}"))
                .isInstanceOf(A2AException.MethodNotFound.class);
        assertThatThrownBy(
                        () ->
                                A2AJsonRpc10.parseRequest(
                                        "{\"id\":1,\"method\":\"GetTask\",\"params\":{}}"))
                .isInstanceOf(A2AException.InvalidParams.class);
        assertThatThrownBy(
                        () ->
                                A2AJsonRpc10.parseRequest(
                                        "{\"id\":1,\"method\":\"SendMessage\",\"params\":{}}"))
                .isInstanceOf(A2AException.InvalidParams.class);
        assertThatThrownBy(
                        () ->
                                A2AJsonRpc10.parseRequest(
                                        "{\"id\":1,\"method\":\"SendMessage\",\"params\":{\"message\":{\"messageId\":\"m\",\"role\":\"ROLE_USER\",\"parts\":[]}}}"))
                .isInstanceOf(A2AException.InvalidParams.class);
    }

    // ---- responses ----

    @Test
    void aTaskResponseIsValidAndRoundTrips() throws Exception {
        var task =
                new RemoteTask(
                        "t-1",
                        "c-1",
                        TaskState.INPUT_REQUIRED,
                        A2AMessage.agent("q-1", List.of(new Part.Text("Which date?"))),
                        List.of(
                                new Artifact(
                                        "a-1",
                                        "Quote",
                                        List.of(
                                                new Part.Text("42"),
                                                Part.FileOffered.link(
                                                        "https://f.example.com/q.pdf",
                                                        "q.pdf",
                                                        "application/pdf")))),
                        List.of(A2AMessage.user("m-1", List.of(new Part.Text("hi")))));
        var json = A2AJsonRpc10.writeResponse(id(1), SEND, new A2AResponse.TaskResponse(task));
        OfficialDefinition.validate("SendMessageResponse", result(json).toString());
        var back = ((SendResult.TaskResult) A2AJsonRpc10.parseSendResponse(json)).task();
        assertThat(back.state()).isEqualTo(TaskState.INPUT_REQUIRED);
        assertThat(back.artifacts()).isEqualTo(task.artifacts());
        assertThat(back.history()).hasSize(1);
    }

    @Test
    void aGetOrCancelAnswersWithTheBareTaskWhileASendWrapsIt() throws Exception {
        var response = new A2AResponse.TaskResponse(RemoteTask.of("t-1", "c", TaskState.WORKING));
        var get =
                result(
                        A2AJsonRpc10.writeResponse(
                                id(1), new A2ARequest.GetTask("t-1", null), response));
        var cancel =
                result(
                        A2AJsonRpc10.writeResponse(
                                id(1), new A2ARequest.CancelTask("t-1"), response));
        var send = result(A2AJsonRpc10.writeResponse(id(1), SEND, response));
        OfficialDefinition.validate("Task", get.toString());
        OfficialDefinition.validate("Task", cancel.toString());
        assertThat(get.has("task")).isFalse();
        assertThat(send.has("task")).isTrue();
    }

    @Test
    void aMessageResponseAndAListResponseAreValid() throws Exception {
        var message = A2AMessage.agent("r-1", List.of(new Part.Text("42 each")));
        OfficialDefinition.validate(
                "SendMessageResponse",
                result(
                                A2AJsonRpc10.writeResponse(
                                        id(1), SEND, new A2AResponse.MessageResponse(message)))
                        .toString());
        var page =
                new A2AResponse.TaskPage(
                        List.of(RemoteTask.of("t-1", "c", TaskState.WORKING)), "", 50, 1);
        var json = A2AJsonRpc10.writeResponse(id(2), LIST, page);
        OfficialDefinition.validate("ListTasksResponse", result(json).toString());
        assertThat(result(json).has("nextPageToken")).isTrue();
        assertThat(A2AJsonRpc10.parseListTasksResponse(json)).hasSize(1);
    }

    @Test
    void everyKnownStateIsWrittenAsAValidWireState() throws Exception {
        for (TaskState state : TaskState.values()) {
            if (state == TaskState.UNKNOWN) continue;
            var json =
                    A2AJsonRpc10.writeResponse(
                            id(1),
                            new A2ARequest.GetTask("t", null),
                            new A2AResponse.TaskResponse(RemoteTask.of("t", "c", state)));
            OfficialDefinition.validate("Task", result(json).toString());
            assertThat(A2AJsonRpc10.parseGetTaskResponse(json).state()).isEqualTo(state);
        }
        assertThatThrownBy(
                        () ->
                                A2AJsonRpc10.writeResponse(
                                        id(1),
                                        new A2ARequest.GetTask("t", null),
                                        new A2AResponse.TaskResponse(
                                                RemoteTask.of("t", "c", TaskState.UNKNOWN))))
                .isInstanceOf(A2AException.class);
    }

    // ---- errors ----

    @ParameterizedTest
    @CsvSource({
        "TaskNotFound,-32001,TASK_NOT_FOUND",
        "TaskNotCancelable,-32002,TASK_NOT_CANCELABLE",
        "UnsupportedOperation,-32004,UNSUPPORTED_OPERATION",
        "ExtensionSupportRequired,-32008,EXTENSION_SUPPORT_REQUIRED",
        "VersionNotSupported,-32009,VERSION_NOT_SUPPORTED"
    })
    void anA2AErrorCarriesItsCodeAndErrorInfo(String type, int code, String reason)
            throws Exception {
        A2AException e =
                switch (type) {
                    case "TaskNotFound" -> new A2AException.TaskNotFound("t-1");
                    case "TaskNotCancelable" -> new A2AException.TaskNotCancelable("t-1");
                    case "UnsupportedOperation" -> new A2AException.UnsupportedOperation("x");
                    case "ExtensionSupportRequired" -> new A2AException.ExtensionSupportRequired(
                            "https://e.example.com/x");
                    default -> new A2AException.VersionNotSupported("0.3");
                };
        var error = JSON.readTree(A2AJsonRpc10.writeError(id(3), e)).get("error");
        assertThat(error.get("code").asInt()).isEqualTo(code);
        assertThat(error.at("/data/0/reason").asText()).isEqualTo(reason);
        assertThat(error.at("/data/0/domain").asText()).isEqualTo("a2a-protocol.org");
        // and our own reader maps it back to the same exception type
        assertThatThrownBy(() -> A2AJsonRpc10.parseSendResponse(A2AJsonRpc10.writeError(id(3), e)))
                .isInstanceOf(e.getClass());
    }

    @Test
    void anInternalFailureNeverLeaksItsMessage() throws Exception {
        var json =
                A2AJsonRpc10.writeError(
                        id(1),
                        new A2AException("jdbc:postgres://db.internal/secret-password failed"));
        assertThat(json).doesNotContain("secret-password").contains("Internal error");
        assertThat(JSON.readTree(json).at("/error/code").asInt()).isEqualTo(-32603);
    }

    // ---- cards ----

    private static AgentCardBuilder builder() {
        return AgentCardBuilder.of("Expense", "Reads a receipt and returns the total.")
                .skill(
                        new AgentCardView.Skill(
                                "read_receipt",
                                "Read a receipt",
                                "Reads a receipt",
                                List.of("expenses"),
                                List.of(),
                                List.of(),
                                List.of()))
                .acceptsMediaType("text/plain")
                .acceptsMediaType("application/json")
                .endpoint(
                        new AgentCardView.Endpoint(
                                "https://agent.example.com/a2a", "JSONRPC", "1.0", null));
    }

    @Test
    void aBuiltCardIsValidAgainstTheOfficialDefinitionAndParsesBack() {
        var published = builder().provider("Example Ltd", "https://example.com").build();
        OfficialDefinition.validate("AgentCard", published.json());
        var back = A2AJsonRpc10.parseCard(published.json());
        assertThat(back.name()).isEqualTo("Expense");
        assertThat(back.endpoints().get(0).protocolVersion()).isEqualTo("1.0");
        assertThat(back.defaultInputMediaTypes()).containsExactly("text/plain", "application/json");
    }

    @Test
    void capabilitiesDefaultToNoStreamingNoPushNoExtendedCard() throws Exception {
        var card = JSON.readTree(builder().build().json());
        assertThat(card.at("/capabilities/streaming").asBoolean(true)).isFalse();
        assertThat(card.at("/capabilities/pushNotifications").asBoolean(true)).isFalse();
        assertThat(card.at("/capabilities/extendedAgentCard").asBoolean(true)).isFalse();
        var on = JSON.readTree(builder().streaming(true).build().json());
        assertThat(on.at("/capabilities/streaming").asBoolean()).isTrue();
    }

    @Test
    void severalInterfacesAreListedInOrderAndATenantIsPassedThrough() throws Exception {
        var published =
                builder()
                        .endpoint(
                                new AgentCardView.Endpoint(
                                        "https://agent.example.com/b", "JSONRPC", "1.0", "acme"))
                        .build();
        OfficialDefinition.validate("AgentCard", published.json());
        var card = JSON.readTree(published.json());
        assertThat(card.at("/supportedInterfaces/0/url").asText()).endsWith("/a2a");
        assertThat(card.at("/supportedInterfaces/1/tenant").asText()).isEqualTo("acme");
    }

    @Test
    void theEtagFollowsTheContentAndNothingElse() {
        var a = builder().build();
        assertThat(builder().build().etag()).isEqualTo(a.etag());
        assertThat(builder().version("2.0.0").build().etag()).isNotEqualTo(a.etag());
        assertThat(a.etag()).startsWith("\"").endsWith("\"");
    }

    @Test
    void aCardNeedsAnEndpointASkillAndAcceptedTypes() {
        assertThatThrownBy(() -> AgentCardBuilder.of("n", "d").build())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                AgentCardBuilder.of("n", "d")
                                        .endpoint(
                                                new AgentCardView.Endpoint(
                                                        "https://a.example.com",
                                                        "JSONRPC",
                                                        "1.0",
                                                        null))
                                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skill");
    }

    // ---- optional operations this agent does not offer (spec 3.3.4), found by the TCK

    @ParameterizedTest
    @CsvSource({
        "SendStreamingMessage, UnsupportedOperation",
        "SubscribeToTask, UnsupportedOperation",
        "GetExtendedAgentCard, UnsupportedOperation",
        "CreateTaskPushNotificationConfig, PushNotificationNotSupported",
        "GetTaskPushNotificationConfig, PushNotificationNotSupported",
        "ListTaskPushNotificationConfigs, PushNotificationNotSupported",
        "DeleteTaskPushNotificationConfig, PushNotificationNotSupported",
        "Frobnicate, MethodNotFound"
    })
    void anOptionalOperationAnAgentDoesNotOfferGetsItsOwnErrorAndAnUnknownOneIsNotFound(
            String method, String error) {
        String request =
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"" + method + "\",\"params\":{}}";

        assertThatThrownBy(() -> A2AJsonRpc10.parseRequest(request))
                .isInstanceOf(A2AException.class)
                .satisfies(e -> assertThat(e.getClass().getSimpleName()).isEqualTo(error));
    }

    @Test
    void pushNotificationsNotSupportedIsCode32003WithItsReason() throws Exception {
        var json =
                JSON.readTree(
                        A2AJsonRpc10.writeError(
                                id(4), new A2AException.PushNotificationNotSupported()));

        assertThat(json.get("error").get("code").asInt()).isEqualTo(-32003);
        assertThat(json.get("error").get("data").get(0).get("reason").asText())
                .isEqualTo("PUSH_NOTIFICATION_NOT_SUPPORTED");
        assertThat(json.get("id").asInt()).isEqualTo(4);
    }

    // ---- the id of a request that could not be parsed (an error must still answer the right
    // request)

    @Test
    void theIdOfARequestIsReadEvenWhenTheRestOfItCannotBeParsed() {
        assertThat(
                        A2AJsonRpc10.idOf(
                                        "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"Frobnicate\"}")
                                .asInt())
                .isEqualTo(42);
        assertThat(A2AJsonRpc10.idOf("{\"id\":\"req-7\",\"method\":1}").asText())
                .isEqualTo("req-7");
        assertThat(A2AJsonRpc10.idOf("{\"method\":\"SendMessage\"}").isNull()).isTrue();
        assertThat(A2AJsonRpc10.idOf("not json").isNull()).isTrue();
        assertThat(A2AJsonRpc10.idOf("[1,2]").isNull()).isTrue();
    }

    // ---- the version a client asks for (spec 3.6.2): matching Major.Minor, or a refusal

    @ParameterizedTest
    @CsvSource({"1.0", "1.0.1", "1.0.0-rc1"})
    void aRequestForVersionOnePointZeroIsAccepted(String version) {
        A2AJsonRpc10.requireSupportedVersion(version);
    }

    @ParameterizedTest
    @CsvSource({"99.0", "0.3", "1.1", "2.0", "1", "abc"})
    void anyOtherVersionIsRefusedWithVersionNotSupported(String version) {
        assertThatThrownBy(() -> A2AJsonRpc10.requireSupportedVersion(version))
                .isInstanceOf(A2AException.VersionNotSupported.class)
                .hasMessageContaining(version);
    }

    @Test
    void noVersionAtAllMeansZeroPointThreeWhichThisHelperDoesNotSpeak() {
        assertThatThrownBy(() -> A2AJsonRpc10.requireSupportedVersion(null))
                .isInstanceOf(A2AException.VersionNotSupported.class)
                .hasMessageContaining("0.3");
        assertThatThrownBy(() -> A2AJsonRpc10.requireSupportedVersion("  "))
                .isInstanceOf(A2AException.VersionNotSupported.class);
    }
}
