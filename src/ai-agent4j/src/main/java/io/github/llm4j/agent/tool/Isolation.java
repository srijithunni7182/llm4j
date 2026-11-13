package io.github.llm4j.agent.tool;

/**
 * How strongly a tool keeps a program it runs apart from the machine it runs on, weakest first. A
 * tool reports the level it actually applied, never the one it was asked for; a tool that runs no
 * program reports {@link #NONE}.
 */
public enum Isolation {
    /** Nothing is kept apart: the tool runs no program, or runs it as the host process would. */
    NONE,
    /**
     * A separate process with limits, on the same machine and kernel. Not isolation in a strong
     * sense.
     */
    PROCESS,
    /** A container: its own file system and network namespace, sharing the host's kernel. */
    CONTAINER,
    /** A virtual machine with its own kernel. */
    VM
}
