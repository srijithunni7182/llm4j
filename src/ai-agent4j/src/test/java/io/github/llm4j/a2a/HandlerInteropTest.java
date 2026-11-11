package io.github.llm4j.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.secret.SecretRef;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Our handler mounted on a loopback server, called with our {@code RemoteAgent} through our helper:
 * the two halves of the same author's reading of the specification agree, and everything the server
 * writes is valid against the official definition. Not a third-party check.
 */
class HandlerInteropTest {

    private static final String PUBLIC = "https://agent.example.test";
    private static final AgentChannel CHANNEL = (c, m) -> new ChannelResponse.Refuse("unused");

    private final FakeTaskRunner runner = new FakeTaskRunner();
    private LoopbackA2AServer server;
    private HttpTestTransport transport;

    @BeforeEach
    void start() throws Exception {
        var card =
                AgentCardBuilder.of("Expense", "Reads a receipt.")
                        .skill(
                                new AgentCardView.Skill(
                                        "read",
                                        "Read",
                                        "Reads",
                                        List.of("x"),
                                        List.of(),
                                        List.of(),
                                        List.of()))
                        .acceptsMediaType("text/plain")
                        .endpoint(
                                new AgentCardView.Endpoint(PUBLIC + "/rpc", "JSONRPC", "1.0", null))
                        .build();
        server = new LoopbackA2AServer(new A2AHandler(scope -> runner), card);
        transport = new HttpTestTransport(PUBLIC, server.loopbackBase());
    }

    @AfterEach
    void stop() {
        assertThat(server.invalidOutput).isEmpty();
        server.close();
    }

    private RemoteAgent agent(String caller) {
        return RemoteAgent.builder(PUBLIC + "/.well-known/agent-card.json", transport, CHANNEL)
                .credential(SecretRef.literal(caller))
                .build();
    }

    private static A2AMessage ask(String id) {
        return A2AMessage.user(id, List.of(new Part.Text("hi")));
    }

    @Test
    void aRemoteAgentCallsOurHandlerThroughTheCardTheBuilderMade() {
        var task = ((SendResult.TaskResult) agent("alice").send(ask("m-1"))).task();
        assertThat(task.state()).isEqualTo(TaskState.WORKING);
        assertThat(runner.calls).containsExactly("start");
    }

    @Test
    void aWaitingSendIsHeldByTheHostAndAnswersWhenTheTaskSettles() {
        runner.autoSettle = true;
        var endpoint = new AgentCardView.Endpoint(PUBLIC + "/rpc", "JSONRPC", "1.0", null);
        var result =
                transport.send(
                        endpoint,
                        SecretRef.literal("alice"),
                        ask("m-1"),
                        new SendOptions(false, List.of(), null));
        assertThat(((SendResult.TaskResult) result).task().state()).isEqualTo(TaskState.COMPLETED);
    }

    @Test
    void replyGetAndCancelWorkAcrossTheWire() {
        runner.put("alice", "t-1", "c-1", TaskState.INPUT_REQUIRED);
        var agent = agent("alice");
        assertThat(agent.get("t-1").state()).isEqualTo(TaskState.INPUT_REQUIRED);
        assertThat(agent.reply("t-1", ask("m-2")).state()).isEqualTo(TaskState.WORKING);
        assertThat(agent.cancel("t-1").state()).isEqualTo(TaskState.CANCELED);
        assertThat(agent.cancel("t-1").state()).isEqualTo(TaskState.CANCELED);
        assertThatThrownBy(() -> agent.get("nope")).isInstanceOf(A2AException.TaskNotFound.class);
    }

    @Test
    void aCallerNeverSeesAnotherCallersTask() {
        runner.put("alice", "t-1", "c-1", TaskState.WORKING);
        assertThatThrownBy(() -> agent("bob").get("t-1"))
                .isInstanceOf(A2AException.TaskNotFound.class);
        assertThat(agent("alice").get("t-1").id()).isEqualTo("t-1");
        var endpoint = new AgentCardView.Endpoint(PUBLIC + "/rpc", "JSONRPC", "1.0", null);
        assertThat(transport.recentTasks(endpoint, SecretRef.literal("bob"), Instant.EPOCH, 5))
                .isEmpty();
        assertThat(transport.recentTasks(endpoint, SecretRef.literal("alice"), Instant.EPOCH, 5))
                .hasSize(1);
    }

    @Test
    void aRequestWithNoIdentityIsRefusedByTheHostAndNeverReachesTheRunner() {
        var endpoint = new AgentCardView.Endpoint(PUBLIC + "/rpc", "JSONRPC", "1.0", null);
        assertThatThrownBy(() -> transport.get(endpoint, null, "t-1"))
                .isInstanceOf(A2AException.class)
                .hasMessageContaining("401");
        assertThat(runner.calls).isEmpty();
    }

    @Test
    void aFinishedTaskRefusesMoreMessagesAsTheSpecificationSays() {
        runner.put("alice", "t-1", "c-1", TaskState.COMPLETED);
        var endpoint = new AgentCardView.Endpoint(PUBLIC + "/rpc", "JSONRPC", "1.0", null);
        assertThatThrownBy(
                        () ->
                                transport.send(
                                        endpoint,
                                        SecretRef.literal("alice"),
                                        ask("m-9").forTask("t-1", null),
                                        SendOptions.immediate()))
                .isInstanceOf(A2AException.UnsupportedOperation.class);
    }
}
