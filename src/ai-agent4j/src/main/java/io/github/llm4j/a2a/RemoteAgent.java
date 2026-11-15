package io.github.llm4j.a2a;

import io.github.llm4j.secret.SecretRef;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A handle on an agent that someone else runs. It checks the card, chooses the endpoint, refuses
 * what must not be sent, and makes one transport call per operation. It never waits, polls or
 * sleeps: whoever drives it decides when to look again. It is deliberately not an implementation of
 * a local agent interface, because a remote agent has no steps or token usage to report.
 *
 * <p>Instances are safe to share between threads.
 */
public final class RemoteAgent {

    /**
     * How far before the recorded send time a search for it starts, to allow for clock differences.
     */
    static final java.time.Duration CLOCK_MARGIN = java.time.Duration.ofMinutes(5);

    /** How many history messages are asked for per task when searching. */
    static final int SEARCH_HISTORY = 20;

    private final String cardAddress;
    private final Set<String> extraHosts;
    private final A2ATransport transport;
    private final AgentChannel channel;
    private final SecretRef credential;
    private final boolean requireSignedCard;
    private AgentCardView card;
    private AgentCardView.Endpoint endpoint;

    private RemoteAgent(Builder b) {
        this.cardAddress = b.cardAddress;
        this.extraHosts = Set.copyOf(b.extraHosts);
        this.transport = b.transport;
        this.channel = b.channel;
        this.credential = b.credential;
        this.requireSignedCard = b.requireSignedCard;
        // fail early on an address that is not usable, before any call
        EndpointGuard.requireHttps(cardAddress, "the card address");
    }

    /**
     * Starts a builder.
     *
     * @param cardAddress the https address of the agent's card, as the script declared it
     * @param transport the consumer's transport
     * @param channel the consumer's channel for questions, authorization requests and files
     */
    public static Builder builder(
            String cardAddress, A2ATransport transport, AgentChannel channel) {
        return new Builder(cardAddress, transport, channel);
    }

    /** Builds a {@link RemoteAgent}. */
    public static final class Builder {
        private final String cardAddress;
        private final A2ATransport transport;
        private final AgentChannel channel;
        private SecretRef credential;
        private boolean requireSignedCard;
        private final Set<String> extraHosts = new HashSet<>();

        private Builder(String cardAddress, A2ATransport transport, AgentChannel channel) {
            this.cardAddress = Objects.requireNonNull(cardAddress, "cardAddress");
            this.transport = Objects.requireNonNull(transport, "transport");
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        /** The credential reference passed to the transport; llm4j never resolves it. */
        public Builder credential(SecretRef value) {
            this.credential = value;
            return this;
        }

        /** Refuses a card that is not {@code VERIFIED}. */
        public Builder requireSignedCard(boolean value) {
            this.requireSignedCard = value;
            return this;
        }

        /** A further host the declaration allows the card to name. */
        public Builder allowHost(String host) {
            this.extraHosts.add(Objects.requireNonNull(host, "host").toLowerCase(Locale.ROOT));
            return this;
        }

        /**
         * Builds the agent.
         *
         * @throws A2AException.Refused if the card address is not an https address
         */
        public RemoteAgent build() {
            return new RemoteAgent(this);
        }
    }

    /** The channel that handles what comes back from this agent. */
    public AgentChannel channel() {
        return channel;
    }

    /**
     * The card as last fetched.
     *
     * @throws IllegalStateException before the first call
     */
    public synchronized AgentCardView card() {
        if (card == null) {
            throw new IllegalStateException("the card has not been fetched yet; make a call first");
        }
        return card;
    }

    /**
     * Sends a message and returns at once.
     *
     * @return a task, or a plain message when the remote made no task
     * @throws A2AException.Refused if the card, version, extensions or file types forbid it
     */
    public SendResult send(A2AMessage message) {
        Objects.requireNonNull(message, "message");
        AgentCardView.Endpoint chosen = prepare();
        checkFileTypes(message);
        return transport.send(chosen, credential, message, SendOptions.immediate());
    }

    /**
     * Answers a task that is waiting on us. A finished task takes no more messages; a refinement is
     * a new {@link #send} that may name the same context.
     *
     * @throws A2AException.Refused if the task is not waiting on the caller
     */
    public RemoteTask reply(String taskId, A2AMessage message) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(message, "message");
        AgentCardView.Endpoint chosen = prepare();
        RemoteTask task = transport.get(chosen, credential, taskId);
        if (task.isTerminal()) {
            throw new A2AException.Refused(
                    "task "
                            + taskId
                            + " has finished ("
                            + task.state()
                            + ") and takes no more messages; make a new send, naming the same context if it is a refinement");
        }
        if (!task.state().isWaitingOnCaller()) {
            throw new A2AException.Refused(
                    "task "
                            + taskId
                            + " is "
                            + task.state()
                            + ", not waiting on the caller; reply only to INPUT_REQUIRED or AUTH_REQUIRED");
        }
        checkFileTypes(message);
        SendResult result =
                transport.send(
                        chosen,
                        credential,
                        message.forTask(taskId, task.contextId()),
                        SendOptions.immediate());
        if (result instanceof SendResult.TaskResult t) {
            return t.task();
        }
        return transport.get(chosen, credential, taskId);
    }

    /** Reads a task once. */
    public RemoteTask get(String taskId) {
        Objects.requireNonNull(taskId, "taskId");
        return transport.get(prepare(), credential, taskId);
    }

    /** Asks the remote to cancel a task; idempotent. */
    public RemoteTask cancel(String taskId) {
        Objects.requireNonNull(taskId, "taskId");
        return transport.cancel(prepare(), credential, taskId);
    }

    /**
     * Looks for the task an earlier send created, after a crash that left it unknown whether the
     * send arrived. It lists recent tasks, with history, since a short margin before {@code sentAt}
     * (clocks differ), and matches the message id in their history. A server may return less
     * history than asked for and cut the message off, or refuse to list: neither is a match, and
     * the caller must then treat the outcome as unknown and send nothing.
     *
     * @param messageId the id of the message that was sent
     * @param sentAt when it was recorded as sent
     * @return the task, or empty if none holds the message
     * @throws A2AException if the transport cannot list
     */
    public Optional<RemoteTask> findBySentMessage(String messageId, Instant sentAt) {
        Objects.requireNonNull(messageId, "the message id");
        Objects.requireNonNull(sentAt, "sentAt");
        List<RemoteTask> recent =
                transport.recentTasks(
                        prepare(), credential, sentAt.minus(CLOCK_MARGIN), SEARCH_HISTORY);
        return recent.stream()
                .filter(t -> t.history().stream().anyMatch(m -> m.messageId().equals(messageId)))
                .findFirst();
    }

    private synchronized AgentCardView.Endpoint prepare() {
        if (endpoint != null) {
            return endpoint;
        }
        AgentCardView fetched = transport.fetchCard(cardAddress);
        if (fetched.signature() == AgentCardView.SignatureStatus.FAILED) {
            throw new A2AException.Refused(
                    "the signature on the card of '"
                            + fetched.name()
                            + "' failed verification; nothing was sent");
        }
        if (requireSignedCard && fetched.signature() != AgentCardView.SignatureStatus.VERIFIED) {
            throw new A2AException.Refused(
                    "the script requires a signed card but the card of '"
                            + fetched.name()
                            + "' is "
                            + fetched.signature()
                            + "; nothing was sent");
        }
        AgentCardView.Endpoint chosen =
                EndpointGuard.select(cardAddress, extraHosts, fetched, transport);
        for (AgentCardView.Extension extension : fetched.extensions()) {
            if (extension.required()
                    && !transport.supportedExtensions().contains(extension.uri())) {
                throw new A2AException.Refused(
                        "the card of '"
                                + fetched.name()
                                + "' requires the extension "
                                + extension.uri()
                                + ", which this transport does not support; nothing was sent");
            }
        }
        this.card = fetched;
        this.endpoint = chosen;
        return chosen;
    }

    private void checkFileTypes(A2AMessage message) {
        Set<String> accepted = acceptedTypes();
        if (accepted.isEmpty()) {
            return;
        }
        for (Part part : message.parts()) {
            if (part instanceof Part.FileOffered file
                    && !isAccepted(accepted, file.claimedMediaType())) {
                throw new A2AException.Refused(
                        "the card accepts "
                                + accepted
                                + " but the file '"
                                + file.label()
                                + "' is "
                                + (file.claimedMediaType().isEmpty()
                                        ? "of no declared type"
                                        : file.claimedMediaType())
                                + "; nothing was sent");
            }
        }
    }

    private Set<String> acceptedTypes() {
        AgentCardView c = card();
        Set<String> accepted = new HashSet<>(c.defaultInputMediaTypes());
        if (accepted.isEmpty()) {
            for (AgentCardView.Skill skill : c.skills()) {
                accepted.addAll(skill.inputMediaTypes());
            }
        }
        return accepted;
    }

    private static boolean isAccepted(Set<String> accepted, String mediaType) {
        String type = mediaType.toLowerCase(Locale.ROOT);
        for (String pattern : accepted) {
            String p = pattern.toLowerCase(Locale.ROOT);
            if (p.equals("*/*")
                    || p.equals(type)
                    || (p.endsWith("/*") && type.startsWith(p.substring(0, p.length() - 1)))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "RemoteAgent["
                + cardAddress
                + ", credential="
                + (credential == null ? "none" : credential)
                + "]";
    }
}
