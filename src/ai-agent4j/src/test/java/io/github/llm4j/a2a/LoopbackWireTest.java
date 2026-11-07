package io.github.llm4j.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.a2a.testkit.MockA2AAgent;
import io.github.llm4j.a2a.testkit.MockA2AAgent.Script;
import io.github.llm4j.a2a.testkit.MockA2AAgent.Step;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Our helper, a consumer-style transport and {@link RemoteAgent} against the mock A2A 1.0 agent over
 * loopback. The mock validates every message against the official definition, so a request the helper
 * builds wrongly is refused. Evidence level: our reading of the specification, checked against its
 * definition and rules; not a third-party implementation.
 */
class LoopbackWireTest {

    private static final String PUBLIC = "https://agent.example.test";
    private static final AgentChannel CHANNEL = (c, m) -> new ChannelResponse.Refuse("unused");

    private MockA2AAgent mock;
    private HttpTestTransport transport;

    @BeforeEach
    void start() {
        mock = MockA2AAgent.start(PUBLIC);
        transport = new HttpTestTransport(PUBLIC, mock.loopbackBase());
    }

    @AfterEach
    void stop() {
        assertThat(mock.selfCheckFailures()).isEmpty();
        mock.close();
    }

    private RemoteAgent agent() {
        return RemoteAgent.builder(mock.cardAddress(), transport, CHANNEL).build();
    }

    private AgentCardView.Endpoint endpoint() {
        return transport.fetchCard(mock.cardAddress()).endpoints().get(0);
    }

    private static A2AMessage ask(String id, String text) {
        return A2AMessage.user(id, List.of(new Part.Text(text)));
    }

    @Test
    void aTaskGoesThroughWorkingAQuestionAnAnswerAndCompletion() {
        mock.script(Script.of(Step.working(), Step.asks("Which date?"), Step.working(),
                Step.completed("[{\"artifactId\":\"a-1\",\"name\":\"Quote\",\"parts\":[{\"text\":\"42 each\"},{\"data\":{\"price\":42}},{\"url\":\"https://f.example.com/q.pdf\",\"filename\":\"q.pdf\",\"mediaType\":\"application/pdf\"}]}]")));
        var agent = agent();
        var task = ((SendResult.TaskResult) agent.send(ask("m-1", "Quote 500 units."))).task();
        assertThat(task.state()).isEqualTo(TaskState.WORKING);
        var asked = agent.get(task.id());
        assertThat(asked.state()).isEqualTo(TaskState.INPUT_REQUIRED);
        assertThat(asked.statusMessage().text()).isEqualTo("Which date?");
        var after = agent.reply(task.id(), ask("m-2", "Within 30 days."));
        assertThat(after.state()).isEqualTo(TaskState.WORKING);
        assertThat(after.contextId()).isEqualTo(task.contextId());
        var done = agent.get(task.id());
        assertThat(done.state()).isEqualTo(TaskState.COMPLETED);
        var parts = done.artifacts().get(0).parts();
        assertThat(parts.get(0)).isEqualTo(new Part.Text("42 each"));
        assertThat(((Part.Data) parts.get(1)).value().get("price").asInt()).isEqualTo(42);
        assertThat(((Part.FileOffered) parts.get(2)).isLink()).isTrue();
        assertThat(mock.receivedMessageIds()).containsExactly("m-1", "m-2");
    }

    @Test
    void theSendAsksNotToBeHeldAndCarriesTheVersion() {
        mock.script(Script.of(Step.working(), Step.completedWithText("ok")));
        agent().send(ask("m-1", "hi"));
        var received = mock.received().stream().filter(r -> r.method().equals("SendMessage")).findFirst().orElseThrow();
        assertThat(received.params().at("/configuration/returnImmediately").asBoolean()).isTrue();
        assertThat(received.headers()).containsEntry("a2a-version", "1.0");
    }

    @Test
    void aPlainMessageAnswerIsHandled() {
        mock.script(Script.plainMessage("42 each"));
        var result = agent().send(ask("m-1", "hi"));
        assertThat(((SendResult.MessageResult) result).message().text()).isEqualTo("42 each");
        assertThat(mock.taskCount()).isZero();
    }

    @Test
    void anAuthorizationRequestArrivesWithItsExplanation() {
        mock.script(Script.of(Step.authRequired("Sign in to the portal")));
        var task = ((SendResult.TaskResult) agent().send(ask("m-1", "hi"))).task();
        assertThat(task.state()).isEqualTo(TaskState.AUTH_REQUIRED);
        assertThat(task.statusMessage().text()).isEqualTo("Sign in to the portal");
    }

    @Test
    void rejectedAndFailedTasksAreReported() {
        mock.script(Script.of(Step.rejected("not our product")));
        assertThat(((SendResult.TaskResult) agent().send(ask("m-1", "hi"))).task().state()).isEqualTo(TaskState.REJECTED);
        mock.script(Script.of(Step.failed("out of stock")));
        assertThat(((SendResult.TaskResult) agent().send(ask("m-2", "hi"))).task().state()).isEqualTo(TaskState.FAILED);
    }

    @Test
    void aCardOnlyOfferingAnotherVersionIsRefusedAndNothingIsSent() {
        mock.interfaces(java.util.Collections.singletonList(new String[] {PUBLIC + "/rpc", "JSONRPC", "0.3", null}));
        assertThatThrownBy(() -> agent().send(ask("m-1", "hi")))
                .isInstanceOf(A2AException.Refused.class)
                .hasMessageContaining("0.3");
        assertThat(mock.received()).isEmpty();
    }

    @Test
    void theFirstSupportedInterfaceWinsAndATenantIsPassedThrough() {
        mock.interfaces(java.util.Arrays.asList(new String[] {PUBLIC + "/rpc", "GRPC", "1.0", null}, new String[] {PUBLIC + "/rpc", "JSONRPC", "1.0", "acme"}));
        agent().send(ask("m-1", "hi"));
        var received = mock.received().get(0);
        assertThat(received.params().get("tenant").asText()).isEqualTo("acme");
    }

    @Test
    void aRequiredExtensionIsRefusedBeforeSendingUnlessTheTransportSupportsIt() {
        mock.requiredExtension("https://e.example.com/geo/v1");
        assertThatThrownBy(() -> agent().send(ask("m-1", "hi"))).isInstanceOf(A2AException.Refused.class).hasMessageContaining("geo/v1");
        assertThat(mock.received()).isEmpty();
        transport.extension("https://e.example.com/geo/v1");
        assertThat(agent().send(ask("m-2", "hi"))).isInstanceOf(SendResult.TaskResult.class);
    }

    @Test
    void aCardPointingElsewhereIsRefusedBeforeAnySend() {
        mock.mode(MockA2AAgent.Mode.CARD_ELSEWHERE);
        assertThatThrownBy(() -> agent().send(ask("m-1", "hi"))).isInstanceOf(A2AException.Refused.class).hasMessageContaining("evil.example.net");
        assertThat(mock.received()).isEmpty();
    }

    @Test
    void anOversizedResponseIsRefusedByTheTransportsBound() {
        mock.mode(MockA2AAgent.Mode.OVERSIZED_RESPONSE);
        assertThatThrownBy(() -> agent().send(ask("m-1", "hi"))).isInstanceOf(A2AException.class).hasMessageContaining("larger than");
    }

    @Test
    void malformedJsonIsAClearErrorAndAnUnknownStateIsUnknownNeverCompleted() {
        mock.mode(MockA2AAgent.Mode.MALFORMED_JSON);
        assertThatThrownBy(() -> agent().send(ask("m-1", "hi"))).isInstanceOf(A2AException.Malformed.class);
        mock.mode(MockA2AAgent.Mode.UNKNOWN_STATE);
        var task = ((SendResult.TaskResult) agent().send(ask("m-2", "hi"))).task();
        assertThat(task.state()).isEqualTo(TaskState.UNKNOWN);
        assertThat(task.isTerminal()).isFalse();
    }

    @Test
    void aSendThatIsReceivedButNeverAnsweredCanBeFoundAgainByItsMessageId() {
        mock.mode(MockA2AAgent.Mode.DROP_AFTER_RECEIVE);
        var before = Instant.now().minusSeconds(5);
        assertThatThrownBy(() -> agent().send(ask("m-crash", "hi"))).isInstanceOf(A2AException.class);
        mock.mode(MockA2AAgent.Mode.NONE);
        var agent = agent();
        var recent = transport.recentTasks(endpoint(), null, before, 10);
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).history()).extracting(A2AMessage::messageId).contains("m-crash");
    }

    @Test
    void aServerThatCutsTheHistoryHidesOurMessageAndTheSearchFindsNothing() {
        mock.script(Script.of(Step.working(), Step.asks("q?")));
        var agent = agent();
        var task = ((SendResult.TaskResult) agent.send(ask("m-1", "hi"))).task();
        agent.get(task.id());
        agent.reply(task.id(), ask("m-2", "ans"));
        mock.historyLimit(1);
        var recent = transport.recentTasks(endpoint(), null, Instant.now().minusSeconds(5), 10);
        assertThat(recent.get(0).history()).extracting(A2AMessage::messageId).doesNotContain("m-1");
    }

    @Test
    void aServerThatWillNotListIsReportedAsUnsupported() {
        mock.listingRefused(true);
        var agent = agent();
        agent.send(ask("m-1", "hi"));
        assertThatThrownBy(() -> transport.recentTasks(endpoint(), null, Instant.now(), 10))
                .isInstanceOf(A2AException.UnsupportedOperation.class);
    }

    @Test
    void cancelAndAFinishedTaskBehaveAsTheSpecificationSays() {
        mock.script(Script.of(Step.working(), Step.completedWithText("ok")));
        var agent = agent();
        var task = ((SendResult.TaskResult) agent.send(ask("m-1", "hi"))).task();
        assertThat(agent.cancel(task.id()).state()).isEqualTo(TaskState.CANCELED);
        assertThat(agent.cancel(task.id()).state()).isEqualTo(TaskState.CANCELED);
        assertThatThrownBy(() -> agent.reply(task.id(), ask("m-2", "more"))).isInstanceOf(A2AException.Refused.class);
        assertThatThrownBy(() -> agent.get("missing")).isInstanceOf(A2AException.TaskNotFound.class);
    }
}
