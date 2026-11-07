package io.github.llm4j.a2a;

/**
 * The consumer's answer to what comes back from another agent that needs a decision: a question, a
 * request for authorization, a file or a link. The consumer decides who answers (a person, a
 * service, a rule) and whether and from where a link is fetched. The runtime wraps every call in an
 * {@link ExchangeGuard}, so the question limit, the timeout and the size of replies hold whatever
 * the implementation does.
 */
@FunctionalInterface
public interface AgentChannel {

    /**
     * Handles one message.
     *
     * @param context a read-only view of where this happens; it shows no variables
     * @param message what to decide; untrusted
     * @return the response; returning null or throwing fails the step
     */
    ChannelResponse handle(ChannelContext context, ChannelMessage message);
}
