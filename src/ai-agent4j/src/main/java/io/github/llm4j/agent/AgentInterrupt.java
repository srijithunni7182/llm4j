package io.github.llm4j.agent;

/**
 * Thrown (by a tool or an approval callback) to stop an agent's run on purpose — for example to
 * suspend a durable workflow until a human answers. Unlike other exceptions, the ReAct loop never
 * turns it into an "error" observation: it always propagates to the caller.
 */
public class AgentInterrupt extends RuntimeException {

    public AgentInterrupt(String message) {
        super(message, null, false, false);
    }
}
