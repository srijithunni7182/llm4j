package io.github.llm4j.a2a;

import io.github.llm4j.secret.SecretRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** A hand-written transport: records every operation and answers from a script. */
final class FakeTransport implements A2ATransport {

    /** One recorded call. */
    record Operation(
            String name, AgentCardView.Endpoint endpoint, SecretRef credential, Object argument) {}

    private final List<Operation> operations = new ArrayList<>();
    private final Deque<SendResult> sendReplies = new LinkedList<>();
    private final Deque<RemoteTask> getReplies = new LinkedList<>();
    private AgentCardView card;
    private Predicate<AgentCardView.Endpoint> supports = e -> true;
    private final Set<String> extensions = new LinkedHashSet<>();
    private List<RemoteTask> recent = List.of();
    private RuntimeException failOnSend;

    FakeTransport card(AgentCardView value) {
        this.card = value;
        return this;
    }

    FakeTransport supporting(Predicate<AgentCardView.Endpoint> value) {
        this.supports = value;
        return this;
    }

    FakeTransport extension(String uri) {
        extensions.add(uri);
        return this;
    }

    FakeTransport sendReplies(SendResult... replies) {
        sendReplies.addAll(List.of(replies));
        return this;
    }

    FakeTransport getReplies(RemoteTask... replies) {
        getReplies.addAll(List.of(replies));
        return this;
    }

    FakeTransport recent(List<RemoteTask> value) {
        this.recent = value;
        return this;
    }

    FakeTransport failSendWith(RuntimeException e) {
        this.failOnSend = e;
        return this;
    }

    List<Operation> operations() {
        return operations;
    }

    List<String> operationNames() {
        return operations.stream().map(Operation::name).toList();
    }

    @Override
    public AgentCardView fetchCard(String address) {
        operations.add(new Operation("fetchCard", null, null, address));
        if (card == null) {
            throw new A2AException("no card scripted");
        }
        return card;
    }

    @Override
    public SendResult send(
            AgentCardView.Endpoint endpoint,
            SecretRef credential,
            A2AMessage message,
            SendOptions options) {
        operations.add(
                new Operation("send", endpoint, credential, new Object[] {message, options}));
        if (failOnSend != null) {
            throw failOnSend;
        }
        return sendReplies.removeFirst();
    }

    @Override
    public RemoteTask get(AgentCardView.Endpoint endpoint, SecretRef credential, String taskId) {
        operations.add(new Operation("get", endpoint, credential, taskId));
        return getReplies.size() > 1 ? getReplies.removeFirst() : getReplies.peekFirst();
    }

    @Override
    public RemoteTask cancel(AgentCardView.Endpoint endpoint, SecretRef credential, String taskId) {
        operations.add(new Operation("cancel", endpoint, credential, taskId));
        return RemoteTask.of(taskId, "", TaskState.CANCELED);
    }

    @Override
    public List<RemoteTask> recentTasks(
            AgentCardView.Endpoint endpoint,
            SecretRef credential,
            Instant since,
            int historyLimit) {
        operations.add(new Operation("recentTasks", endpoint, credential, since));
        return recent;
    }

    @Override
    public boolean supports(AgentCardView.Endpoint endpoint) {
        return supports.test(endpoint);
    }

    @Override
    public Set<String> supportedExtensions() {
        return Set.copyOf(extensions);
    }
}
