package io.github.llm4j.loom.runtime;

/**
 * Standard interface for human-in-the-loop interactions within a Loom workflow.
 */
public interface HumanInterface {
    /**
     * Prompts a human user for input.
     * 
     * @param message the message/prompt to display to the human
     * @return the human's response
     */
    String promptHuman(String message);
}
