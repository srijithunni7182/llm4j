package io.github.llm4j.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.secret.SecretMetadata;
import io.github.llm4j.secret.SecretRef;
import io.github.llm4j.secret.SecretStore;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RemoteAgentTest {

    private static final String ADDRESS =
            "https://supplier.example.com/.well-known/agent-card.json";
    private static final AgentCardView.Endpoint ENDPOINT =
            new AgentCardView.Endpoint("https://supplier.example.com/rpc", "JSONRPC", "1.0", null);
    private static final AgentChannel CHANNEL = (c, m) -> new ChannelResponse.Refuse("not used");

    private static AgentCardView.Builder card() {
        return AgentCardView.builder("Supplier", "sells").endpoints(List.of(ENDPOINT));
    }

    private static A2AMessage hello() {
        return A2AMessage.user("m-1", List.of(new Part.Text("Quote 500 units.")));
    }

    private static RemoteAgent agent(FakeTransport transport) {
        return RemoteAgent.builder(ADDRESS, transport, CHANNEL).build();
    }

    private static SendResult.TaskResult working() {
        return new SendResult.TaskResult(RemoteTask.of("t-1", "c-1", TaskState.WORKING));
    }

    @Test
    void sendFetchesTheCardOnceThenSendsImmediately() {
        var transport = new FakeTransport().card(card().build()).sendReplies(working(), working());
        var agent = agent(transport);
        agent.send(hello());
        agent.send(A2AMessage.user("m-2", List.of(new Part.Text("again"))));
        assertThat(transport.operationNames()).containsExactly("fetchCard", "send", "send");
        var options = (SendOptions) ((Object[]) transport.operations().get(1).argument())[1];
        assertThat(options.returnImmediately()).isTrue();
    }

    @Test
    void aSendMayReturnAPlainMessageAndNoTask() {
        var reply = A2AMessage.agent("r-1", List.of(new Part.Text("42 each")));
        var transport =
                new FakeTransport()
                        .card(card().build())
                        .sendReplies(new SendResult.MessageResult(reply));
        assertThat(agent(transport).send(hello())).isEqualTo(new SendResult.MessageResult(reply));
    }

    @Test
    void aCardPointingElsewhereIsRefusedAndNothingIsSent() {
        var bad =
                AgentCardView.builder("Evil", "x")
                        .endpoints(
                                List.of(
                                        new AgentCardView.Endpoint(
                                                "https://evil.example.net/rpc",
                                                "JSONRPC",
                                                "1.0",
                                                null)))
                        .build();
        var transport = new FakeTransport().card(bad);
        assertThatThrownBy(() -> agent(transport).send(hello()))
                .isInstanceOf(A2AException.Refused.class);
        assertThat(transport.operationNames()).containsExactly("fetchCard");
    }

    @Test
    void aFailedSignatureIsAlwaysRefused() {
        var transport =
                new FakeTransport()
                        .card(card().signature(AgentCardView.SignatureStatus.FAILED).build());
        assertThatThrownBy(() -> agent(transport).send(hello()))
                .isInstanceOf(A2AException.Refused.class)
                .hasMessageContaining("signature");
        assertThat(transport.operationNames()).doesNotContain("send");
    }

    @Test
    void requireSignedCardRefusesAnythingNotVerified() {
        for (var status :
                List.of(
                        AgentCardView.SignatureStatus.NOT_SIGNED,
                        AgentCardView.SignatureStatus.NOT_CHECKED)) {
            var transport = new FakeTransport().card(card().signature(status).build());
            var strict =
                    RemoteAgent.builder(ADDRESS, transport, CHANNEL)
                            .requireSignedCard(true)
                            .build();
            assertThatThrownBy(() -> strict.send(hello())).isInstanceOf(A2AException.Refused.class);
            assertThat(transport.operationNames()).doesNotContain("send");
        }
        var ok =
                new FakeTransport()
                        .card(card().signature(AgentCardView.SignatureStatus.VERIFIED).build())
                        .sendReplies(working());
        RemoteAgent.builder(ADDRESS, ok, CHANNEL).requireSignedCard(true).build().send(hello());
        assertThat(ok.operationNames()).contains("send");
    }

    @Test
    void anUnsignedCardGoesAheadWhenNotRequiredAndTheStatusIsVisible() {
        var transport =
                new FakeTransport()
                        .card(card().signature(AgentCardView.SignatureStatus.NOT_SIGNED).build())
                        .sendReplies(working());
        var agent = agent(transport);
        agent.send(hello());
        assertThat(agent.card().signature()).isEqualTo(AgentCardView.SignatureStatus.NOT_SIGNED);
    }

    @Test
    void aRequiredExtensionTheTransportLacksIsRefusedBeforeSending() {
        var c =
                card().extensions(
                                List.of(
                                        new AgentCardView.Extension(
                                                "https://ext.example.com/geo/v1", true)))
                        .build();
        var transport = new FakeTransport().card(c);
        assertThatThrownBy(() -> agent(transport).send(hello()))
                .isInstanceOf(A2AException.Refused.class)
                .hasMessageContaining("https://ext.example.com/geo/v1");
        assertThat(transport.operationNames()).doesNotContain("send");
        var supported =
                new FakeTransport()
                        .card(c)
                        .extension("https://ext.example.com/geo/v1")
                        .sendReplies(working());
        agent(supported).send(hello());
        assertThat(supported.operationNames()).contains("send");
    }

    @Test
    void anOptionalExtensionIsNoObstacle() {
        var c =
                card().extensions(
                                List.of(
                                        new AgentCardView.Extension(
                                                "https://ext.example.com/x", false)))
                        .build();
        var transport = new FakeTransport().card(c).sendReplies(working());
        agent(transport).send(hello());
        assertThat(transport.operationNames()).contains("send");
    }

    @Test
    void aFileOfATypeTheCardDoesNotAcceptIsRefusedBeforeSending() {
        var c = card().defaultInputMediaTypes(List.of("text/plain", "image/*")).build();
        var transport = new FakeTransport().card(c).sendReplies(working());
        var pdf =
                A2AMessage.user(
                        "m",
                        List.of(Part.FileOffered.inline(new byte[] {1}, "a", "application/pdf")));
        assertThatThrownBy(() -> agent(transport).send(pdf))
                .isInstanceOf(A2AException.Refused.class)
                .hasMessageContaining("application/pdf");
        var png =
                A2AMessage.user(
                        "m2", List.of(Part.FileOffered.inline(new byte[] {1}, "a", "image/png")));
        agent(transport).send(png);
        assertThat(transport.operationNames()).contains("send");
    }

    @Test
    void aFileWithNoClaimedTypeIsRefusedWhenTheCardDeclaresTypes() {
        var c = card().defaultInputMediaTypes(List.of("image/png")).build();
        var transport = new FakeTransport().card(c);
        var untyped =
                A2AMessage.user(
                        "m", List.of(Part.FileOffered.link("https://f.example.com/x", "x", "")));
        assertThatThrownBy(() -> agent(transport).send(untyped))
                .isInstanceOf(A2AException.Refused.class);
    }

    @Test
    void aCardWithNoInputTypesSendsTheFile() {
        var transport = new FakeTransport().card(card().build()).sendReplies(working());
        var pdf =
                A2AMessage.user(
                        "m",
                        List.of(Part.FileOffered.inline(new byte[] {1}, "a", "application/pdf")));
        agent(transport).send(pdf);
        assertThat(transport.operationNames()).contains("send");
    }

    @Test
    void replyOnATerminalTaskIsRefusedAndNothingIsSent() {
        var transport =
                new FakeTransport()
                        .card(card().build())
                        .getReplies(RemoteTask.of("t-1", "c-1", TaskState.COMPLETED));
        assertThatThrownBy(() -> agent(transport).reply("t-1", hello()))
                .isInstanceOf(A2AException.Refused.class)
                .hasMessageContaining("new send");
        assertThat(transport.operationNames()).doesNotContain("send");
    }

    @Test
    void replyOnATaskThatIsNotWaitingOnUsIsRefused() {
        var transport =
                new FakeTransport()
                        .card(card().build())
                        .getReplies(RemoteTask.of("t-1", "c-1", TaskState.WORKING));
        assertThatThrownBy(() -> agent(transport).reply("t-1", hello()))
                .isInstanceOf(A2AException.Refused.class);
    }

    @Test
    void replyOnAWaitingTaskIsSentOnTheSameTaskAndContext() {
        var waiting = RemoteTask.of("t-1", "c-1", TaskState.INPUT_REQUIRED);
        var next = RemoteTask.of("t-1", "c-1", TaskState.WORKING);
        var transport =
                new FakeTransport()
                        .card(card().build())
                        .getReplies(waiting)
                        .sendReplies(new SendResult.TaskResult(next));
        var result = agent(transport).reply("t-1", hello());
        assertThat(result).isEqualTo(next);
        var sent = (A2AMessage) ((Object[]) transport.operations().get(2).argument())[0];
        assertThat(sent.taskId()).isEqualTo("t-1");
        assertThat(sent.contextId()).isEqualTo("c-1");
    }

    @Test
    void getAndCancelAreSingleCallsThatNeverWait() {
        var transport =
                new FakeTransport()
                        .card(card().build())
                        .getReplies(RemoteTask.of("t-1", "c-1", TaskState.WORKING));
        var agent = agent(transport);
        assertThat(agent.get("t-1").state()).isEqualTo(TaskState.WORKING);
        assertThat(agent.cancel("t-1").state()).isEqualTo(TaskState.CANCELED);
        assertThat(transport.operationNames()).containsExactly("fetchCard", "get", "cancel");
    }

    @Test
    void aFailureFromTheTransportPropagatesUnchanged() {
        var boom = new A2AException.VersionNotSupported("1.0");
        var transport = new FakeTransport().card(card().build()).failSendWith(boom);
        assertThatThrownBy(() -> agent(transport).send(hello())).isSameAs(boom);
    }

    @Test
    void anEarlierSendIsFoundByItsMessageIdInTheHistoryOfRecentTasks() {
        var ours =
                new RemoteTask(
                        "t-1", "c", TaskState.WORKING, null, List.of(), List.of(ask("m-ours")));
        var other =
                new RemoteTask(
                        "t-2", "c", TaskState.WORKING, null, List.of(), List.of(ask("m-other")));
        var transport = new FakeTransport().card(card().build()).recent(List.of(other, ours));
        var sentAt = java.time.Instant.parse("2026-11-07T10:00:00Z");

        var found = agent(transport).findBySentMessage("m-ours", sentAt);

        assertThat(found).map(RemoteTask::id).contains("t-1");
        var listed =
                transport.operations().stream()
                        .filter(o -> o.name().equals("recentTasks"))
                        .findFirst()
                        .orElseThrow();
        assertThat((java.time.Instant) listed.argument()).isBefore(sentAt);
    }

    @Test
    void nothingIsFoundWhenNoRecentTaskHoldsTheMessageIdAndNothingIsSentToLook() {
        var transport =
                new FakeTransport()
                        .card(card().build())
                        .recent(List.of(RemoteTask.of("t-9", "c", TaskState.WORKING)));
        assertThat(agent(transport).findBySentMessage("m-ours", java.time.Instant.now())).isEmpty();
        assertThat(transport.operationNames()).doesNotContain("send");
    }

    private static A2AMessage ask(String id) {
        return A2AMessage.user(id, List.of(new Part.Text("hi")));
    }

    private static final class CountingStore implements SecretStore {
        final AtomicInteger resolves = new AtomicInteger();

        @Override
        public String resolve(String name) {
            resolves.incrementAndGet();
            return "sk-test-000";
        }

        @Override
        public boolean contains(String name) {
            return true;
        }

        @Override
        public Set<String> names() {
            return Set.of("SUPPLIER_KEY");
        }

        @Override
        public Optional<SecretMetadata> metadata(String name) {
            return Optional.of(SecretMetadata.NONE);
        }

        @Override
        public void close() {}
    }

    @Test
    void theCredentialIsPassedAsAReferenceAndNeverResolvedByUs() {
        var store = new CountingStore();
        var ref = SecretRef.of(store, "SUPPLIER_KEY");
        var transport =
                new FakeTransport()
                        .card(card().build())
                        .sendReplies(working())
                        .getReplies(RemoteTask.of("t-1", "c-1", TaskState.WORKING));
        var agent = RemoteAgent.builder(ADDRESS, transport, CHANNEL).credential(ref).build();
        agent.send(hello());
        agent.get("t-1");
        agent.cancel("t-1");
        assertThat(transport.operations().stream().filter(o -> !o.name().equals("fetchCard")))
                .allMatch(o -> o.credential() == ref);
        assertThat(store.resolves).hasValue(0);
        assertThat(agent.toString()).doesNotContain("sk-test-000").contains("secret:SUPPLIER_KEY");
    }

    @Test
    void aRemoteAgentIsNotAnAgentAndHoldsTheChannel() {
        assertThat(RemoteAgent.class.getInterfaces()).isEmpty();
        var agent = RemoteAgent.builder(ADDRESS, new FakeTransport(), CHANNEL).build();
        assertThat(agent.channel()).isSameAs(CHANNEL);
    }

    @Test
    void theDeclaredAddressMustBeHttpsAtConstruction() {
        assertThatThrownBy(
                        () ->
                                RemoteAgent.builder(
                                                "http://supplier.example.com/c.json",
                                                new FakeTransport(),
                                                CHANNEL)
                                        .build())
                .isInstanceOf(A2AException.Refused.class);
    }
}
