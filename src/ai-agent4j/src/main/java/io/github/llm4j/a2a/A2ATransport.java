package io.github.llm4j.a2a;

import io.github.llm4j.secret.SecretRef;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * The one way anything leaves the process. The consumer implements it: llm4j ships no default. An
 * implementation speaks whatever binding and protocol version the remote uses, adds headers and
 * retries, resolves {@code credential} for the host the endpoint names (never for another host),
 * refuses private addresses and unsafe redirects, bounds the size of responses, and may verify a
 * card's signature. It translates between the wire and this package's version-free model; {@link
 * A2AJsonRpc10} does that for A2A 1.0 over JSON-RPC.
 *
 * <p>Each method may throw an unchecked {@link A2AException} subclass. A {@code credential} is a
 * reference and is never null-checked by llm4j: null means the call is made without one.
 */
public interface A2ATransport {

    /**
     * Fetches and parses a card.
     *
     * @param address the https address the script declared
     * @return the card; its {@link AgentCardView#signature()} says what the transport verified
     */
    AgentCardView fetchCard(String address);

    /**
     * Sends a message.
     *
     * @param endpoint where to send it
     * @param credential a reference to the credential for the endpoint's host, or null
     * @param message the message
     * @param options how to send; llm4j always sets {@code returnImmediately}
     * @return a task or a plain message
     */
    SendResult send(
            AgentCardView.Endpoint endpoint,
            SecretRef credential,
            A2AMessage message,
            SendOptions options);

    /** Reads a task. */
    RemoteTask get(AgentCardView.Endpoint endpoint, SecretRef credential, String taskId);

    /** Asks the remote to cancel a task; idempotent. */
    RemoteTask cancel(AgentCardView.Endpoint endpoint, SecretRef credential, String taskId);

    /**
     * Lists tasks updated since a time, with history, for finding an earlier send after a crash.
     *
     * @param since only tasks updated at or after this
     * @param historyLimit how many history messages to ask for per task
     */
    List<RemoteTask> recentTasks(
            AgentCardView.Endpoint endpoint, SecretRef credential, Instant since, int historyLimit);

    /** Whether this transport can speak to the endpoint's binding and protocol version. */
    boolean supports(AgentCardView.Endpoint endpoint);

    /** The extension URIs this transport supports. */
    Set<String> supportedExtensions();
}
