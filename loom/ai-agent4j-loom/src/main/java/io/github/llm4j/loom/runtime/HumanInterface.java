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

    /**
     * Asks a human at a known step. Return the answer to continue now, or throw {@link RunSuspended}
     * to pause the run without holding a thread; resume it later with the answer in the journal.
     *
     * @param stepId the step's stable id in the run journal
     */
    default String promptHuman(String stepId, String message) {
        return promptHuman(message);
    }
}
