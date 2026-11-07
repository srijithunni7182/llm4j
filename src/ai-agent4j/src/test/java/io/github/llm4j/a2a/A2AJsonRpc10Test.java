package io.github.llm4j.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.llm4j.a2a.testkit.OfficialDefinition;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class A2AJsonRpc10Test {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode params(A2AJsonRpc10.Request request) throws Exception {
        return JSON.readTree(request.body()).get("params");
    }

    private static A2AMessage hello() {
        return A2AMessage.user("m-1", List.of(new Part.Text("Quote 500 units of part 12.")));
    }

    // ---- requests: every one is valid against the official definition ----

    @Test
    void aSendRequestIsValidSetsReturnImmediatelyAndCarriesTheVersionHeader() throws Exception {
        var request = A2AJsonRpc10.sendMessage(1, hello(), SendOptions.immediate(), null);
        var root = JSON.readTree(request.body());
        assertThat(root.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(root.get("method").asText()).isEqualTo("SendMessage");
        OfficialDefinition.validate("SendMessageRequest", params(request).toString());
        assertThat(params(request).at("/configuration/returnImmediately").asBoolean()).isTrue();
        assertThat(request.headers()).containsEntry("A2A-Version", "1.0");
    }

    @Test
    void aSendWithAFilesAndDataAndATaskIsValid() throws Exception {
        var message = new A2AMessage(
                "m-2", A2AMessage.Role.USER,
                List.of(new Part.Text("see"), new Part.Data(JsonNodeFactory.instance.objectNode().put("n", 3)),
                        Part.FileOffered.inline(new byte[] {1, 2, 3}, "a.png", "image/png"),
                        Part.FileOffered.link("https://f.example.com/a.pdf", "a.pdf", "application/pdf")),
                "c-1", "t-1", List.of("t-0"));
        var request = A2AJsonRpc10.sendMessage(2, message, new SendOptions(true, List.of("text/plain"), 5), "acme");
        OfficialDefinition.validate("SendMessageRequest", params(request).toString());
        assertThat(params(request).get("tenant").asText()).isEqualTo("acme");
        assertThat(params(request).at("/message/parts/2/raw").asText()).isEqualTo("AQID");
    }

    @Test
    void getCancelAndListRequestsAreValid() throws Exception {
        var get = A2AJsonRpc10.getTask(3, "t-1", 10, null);
        assertThat(JSON.readTree(get.body()).get("method").asText()).isEqualTo("GetTask");
        OfficialDefinition.validate("GetTaskRequest", params(get).toString());
        var cancel = A2AJsonRpc10.cancelTask(4, "t-1", null);
        assertThat(JSON.readTree(cancel.body()).get("method").asText()).isEqualTo("CancelTask");
        OfficialDefinition.validate("CancelTaskRequest", params(cancel).toString());
        var list = A2AJsonRpc10.listTasks(5, Instant.parse("2026-11-07T10:00:00Z"), 20, 50, null);
        assertThat(JSON.readTree(list.body()).get("method").asText()).isEqualTo("ListTasks");
        OfficialDefinition.validate("ListTasksRequest", params(list).toString());
        assertThat(params(list).get("statusTimestampAfter").asText()).isEqualTo("2026-11-07T10:00:00Z");
    }

    @Test
    void extensionsTravelInTheirOwnHeader() {
        var headers = A2AJsonRpc10.serviceParameters(List.of("https://e.example.com/a", "https://e.example.com/b"));
        assertThat(headers.get("A2A-Extensions")).isEqualTo("https://e.example.com/a,https://e.example.com/b");
        assertThat(A2AJsonRpc10.serviceParameters(List.of())).doesNotContainKey("A2A-Extensions");
    }

    // ---- responses ----

    @Test
    void aTaskResponseIsParsedWithItsQuestionAndArtifacts() {
        var result = A2AJsonRpc10.parseSendResponse("""
            {"jsonrpc":"2.0","id":1,"result":{"task":{"id":"t-77","contextId":"c-9",
              "status":{"state":"TASK_STATE_INPUT_REQUIRED","message":{"messageId":"q-1","role":"ROLE_AGENT","parts":[{"text":"Which date?"}]}},
              "artifacts":[{"artifactId":"a-1","name":"Quote","parts":[{"text":"42 each"},{"url":"https://f.example.com/q.pdf","filename":"q.pdf","mediaType":"application/pdf"},{"data":{"price":42}}]}],
              "extraFieldFromTheFuture":true}}}""");
        var task = ((SendResult.TaskResult) result).task();
        assertThat(task.id()).isEqualTo("t-77");
        assertThat(task.state()).isEqualTo(TaskState.INPUT_REQUIRED);
        assertThat(task.statusMessage().text()).isEqualTo("Which date?");
        assertThat(task.artifacts()).hasSize(1);
        var parts = task.artifacts().get(0).parts();
        assertThat(parts.get(1)).isEqualTo(Part.FileOffered.link("https://f.example.com/q.pdf", "q.pdf", "application/pdf"));
        assertThat(((Part.Data) parts.get(2)).value().get("price").asInt()).isEqualTo(42);
    }

    @Test
    void aPlainMessageResponseIsParsedAsAMessage() {
        var result = A2AJsonRpc10.parseSendResponse("""
            {"jsonrpc":"2.0","id":1,"result":{"message":{"messageId":"r-1","role":"ROLE_AGENT","parts":[{"text":"42 each"}]}}}""");
        assertThat(((SendResult.MessageResult) result).message().text()).isEqualTo("42 each");
    }

    @Test
    void aRawPartBecomesAnInlineFileWithTheDecodedBytes() {
        var result = A2AJsonRpc10.parseSendResponse("""
            {"result":{"message":{"messageId":"r","role":"ROLE_AGENT","parts":[{"raw":"AQID","filename":"a.bin","mediaType":"application/octet-stream"}]}}}""");
        var file = (Part.FileOffered) ((SendResult.MessageResult) result).message().parts().get(0);
        assertThat(file.bytes()).containsExactly(1, 2, 3);
        assertThat(file.label()).isEqualTo("a.bin");
    }

    @ParameterizedTest
    @CsvSource({
        "TASK_STATE_SUBMITTED,WORKING", "TASK_STATE_WORKING,WORKING", "TASK_STATE_COMPLETED,COMPLETED",
        "TASK_STATE_FAILED,FAILED", "TASK_STATE_CANCELED,CANCELED", "TASK_STATE_INPUT_REQUIRED,INPUT_REQUIRED",
        "TASK_STATE_REJECTED,REJECTED", "TASK_STATE_AUTH_REQUIRED,AUTH_REQUIRED",
        "completed,COMPLETED", "input-required,INPUT_REQUIRED", "auth_required,AUTH_REQUIRED", "submitted,WORKING",
        "canceled,CANCELED", "cancelled,CANCELED", "TASK_STATE_UNSPECIFIED,UNKNOWN", "something_new,UNKNOWN"
    })
    void bothStateSpellingsAreAcceptedAndAnythingElseIsUnknown(String wire, TaskState expected) {
        var json = "{\"result\":{\"task\":{\"id\":\"t\",\"status\":{\"state\":\"" + wire + "\"}}}}";
        assertThat(((SendResult.TaskResult) A2AJsonRpc10.parseSendResponse(json)).task().state()).isEqualTo(expected);
    }

    @Test
    void aMissingStateIsUnknownNeverCompleted() {
        var json = "{\"result\":{\"task\":{\"id\":\"t\",\"status\":{}}}}";
        assertThat(((SendResult.TaskResult) A2AJsonRpc10.parseSendResponse(json)).task().state()).isEqualTo(TaskState.UNKNOWN);
    }

    @Test
    void getTaskAndListResponsesAreParsedWithHistory() {
        var task = A2AJsonRpc10.parseGetTaskResponse("""
            {"result":{"id":"t-1","contextId":"c","status":{"state":"TASK_STATE_WORKING"},
              "history":[{"messageId":"m-1","role":"ROLE_USER","parts":[{"text":"hi"}]}]}}""");
        assertThat(task.history()).hasSize(1);
        assertThat(task.history().get(0).messageId()).isEqualTo("m-1");
        var list = A2AJsonRpc10.parseListTasksResponse("""
            {"result":{"tasks":[{"id":"a","status":{"state":"TASK_STATE_COMPLETED"}},{"id":"b","status":{"state":"TASK_STATE_WORKING"}}],"nextPageToken":"","pageSize":50,"totalSize":2}}""");
        assertThat(list).extracting(RemoteTask::id).containsExactly("a", "b");
    }

    @ParameterizedTest
    @CsvSource({"-32001,TaskNotFound", "-32002,TaskNotCancelable", "-32004,UnsupportedOperation",
                "-32005,ContentTypeNotSupported", "-32008,ExtensionSupportRequired", "-32009,VersionNotSupported"})
    void protocolErrorsBecomeTheirOwnExceptions(int code, String simpleName) {
        var json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":" + code + ",\"message\":\"nope t-9\",\"data\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"X\",\"domain\":\"a2a-protocol.org\"}]}}";
        assertThatThrownBy(() -> A2AJsonRpc10.parseSendResponse(json))
                .isInstanceOf(A2AException.class)
                .satisfies(e -> assertThat(e.getClass().getSimpleName()).isEqualTo(simpleName));
    }

    @Test
    void anotherErrorCodeIsAGenericA2AExceptionThatDoesNotEchoTheWireCodeAsAType() {
        assertThatThrownBy(() -> A2AJsonRpc10.parseSendResponse("{\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}"))
                .isInstanceOf(A2AException.class)
                .hasMessageContaining("Internal error");
    }

    @Test
    void malformedInputIsAClearErrorNotAnNpe() {
        for (String bad : List.of("", "not json", "[]", "{}", "{\"result\":{}}", "{\"result\":{\"task\":{\"status\":{}}}}",
                "{\"result\":{\"message\":{\"messageId\":\"m\",\"role\":\"ROLE_AGENT\",\"parts\":[{}]}}}",
                "{\"result\":{\"message\":{\"messageId\":\"m\",\"role\":\"ROLE_AGENT\",\"parts\":[]}}}")) {
            assertThatThrownBy(() -> A2AJsonRpc10.parseSendResponse(bad)).as(bad).isInstanceOf(A2AException.Malformed.class);
        }
    }

    // ---- cards ----

    @Test
    void aCardIsParsedWithEndpointsInOrderExtensionsAndSkills() {
        var card = A2AJsonRpc10.parseCard("""
            {"name":"Supplier","description":"sells","version":"2","capabilities":{"extensions":[{"uri":"https://e.example.com/geo/v1","required":true},{"uri":"https://e.example.com/opt"}]},
             "supportedInterfaces":[{"url":"https://s.example.com/rpc","protocolBinding":"JSONRPC","protocolVersion":"1.0","tenant":"acme"},{"url":"https://s.example.com/old","protocolBinding":"JSONRPC","protocolVersion":"0.3"}],
             "defaultInputModes":["text/plain","image/png"],"defaultOutputModes":["text/plain"],
             "securitySchemes":{"bearer":{"httpAuthSecurityScheme":{"scheme":"Bearer"}}},
             "skills":[{"id":"quote","name":"Quote","description":"prices","tags":["buy"],"inputModes":["text/plain"]}]}""");
        assertThat(card.name()).isEqualTo("Supplier");
        assertThat(card.endpoints()).extracting(AgentCardView.Endpoint::protocolVersion).containsExactly("1.0", "0.3");
        assertThat(card.endpoints().get(0).tenant()).isEqualTo("acme");
        assertThat(card.extensions()).extracting(AgentCardView.Extension::required).containsExactly(true, false);
        assertThat(card.defaultInputMediaTypes()).containsExactly("text/plain", "image/png");
        assertThat(card.authSchemes()).containsExactly("bearer");
        assertThat(card.skills().get(0).inputMediaTypes()).containsExactly("text/plain");
        assertThat(card.signature()).isEqualTo(AgentCardView.SignatureStatus.NOT_SIGNED);
    }

    @Test
    void aCardWithSignaturesIsNotCheckedByUsAndTheTransportReportsTheResult() {
        var card = A2AJsonRpc10.parseCard("""
            {"name":"S","supportedInterfaces":[],"signatures":[{"protected":"e30","signature":"abc"}]}""");
        assertThat(card.signature()).isEqualTo(AgentCardView.SignatureStatus.NOT_CHECKED);
    }

    @Test
    void anOlderCardWithASingleAddressOffersThatEndpointWithItsOwnVersion() {
        var card = A2AJsonRpc10.parseCard("""
            {"name":"Old","url":"https://o.example.com/rpc","protocolVersion":"0.3.0","preferredTransport":"JSONRPC"}""");
        assertThat(card.endpoints()).hasSize(1);
        assertThat(card.endpoints().get(0).protocolVersion()).isEqualTo("0.3.0");
    }

    @Test
    void aCardWithNoNameIsMalformed() {
        assertThatThrownBy(() -> A2AJsonRpc10.parseCard("{\"description\":\"x\"}")).isInstanceOf(A2AException.Malformed.class);
    }

    @Test
    void theHelperSupportsExactlyTheJsonRpcVersionOneEndpoints() {
        assertThat(A2AJsonRpc10.supports(new AgentCardView.Endpoint("https://x.example.com", "JSONRPC", "1.0", null))).isTrue();
        assertThat(A2AJsonRpc10.supports(new AgentCardView.Endpoint("https://x.example.com", "jsonrpc", "1.0", null))).isTrue();
        assertThat(A2AJsonRpc10.supports(new AgentCardView.Endpoint("https://x.example.com", "JSONRPC", "1", null))).isFalse();
        assertThat(A2AJsonRpc10.supports(new AgentCardView.Endpoint("https://x.example.com", "JSONRPC", "0.3", null))).isFalse();
        assertThat(A2AJsonRpc10.supports(new AgentCardView.Endpoint("https://x.example.com", "GRPC", "1.0", null))).isFalse();
    }

    // ---- principle 9 ----

    @Test
    void onlyThisClassKnowsAWireName() throws Exception {
        var wire = List.of("TASK_STATE_", "ROLE_USER", "ROLE_AGENT", "supportedInterfaces", "protocolBinding",
                "A2A-Version", "A2A-Extensions", "\"SendMessage\"", "\"GetTask\"", "\"CancelTask\"", "\"ListTasks\"",
                "\"returnImmediately\"", "\"messageId\"", "\"contextId\"", "\"artifactId\"", "\"JSONRPC\"");
        var dir = java.nio.file.Path.of("src/main/java/io/github/llm4j/a2a");
        try (var files = java.nio.file.Files.list(dir)) {
            for (var file : files.filter(f -> f.toString().endsWith(".java") && !f.getFileName().toString().equals("A2AJsonRpc10.java")).toList()) {
                var text = java.nio.file.Files.readString(file);
                for (String name : wire) {
                    assertThat(text).as(file.getFileName() + " mentions the wire name " + name).doesNotContain(name);
                }
            }
        }
    }
}
