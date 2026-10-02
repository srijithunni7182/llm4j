package io.github.llm4j.tools;

/** A call a tool won't make (bad argument, policy, a failure that provably changed nothing). Shown to the agent. */
public class ToolRefusal extends RuntimeException {

    public ToolRefusal(String message) {
        super(message);
    }

    public ToolRefusal(String message, Throwable cause) {
        super(message, cause);
    }
}
