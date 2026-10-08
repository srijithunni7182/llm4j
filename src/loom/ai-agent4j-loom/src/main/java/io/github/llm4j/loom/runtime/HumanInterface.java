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

    /**
     * What the asker knows about the question, so a channel can present it well (the words to reply with) and safely (an approval needs more than a bare reply).
     *
     * @param kind    what is being asked
     * @param choices the answers that make sense, or an empty list for free text
     * @param to      the name the script asked ({@code ask: support-lead}), or null
     */
    record Hints(Kind kind, java.util.List<String> choices, String to) {
        public enum Kind { PROMPT, APPROVAL, DECIDE }

        public Hints {
            choices = choices == null ? java.util.List.of() : java.util.List.copyOf(choices);
        }

        public static Hints none() {
            return new Hints(Kind.PROMPT, java.util.List.of(), null);
        }
    }

    /** As {@link #promptHuman(String, String)}, with what is known about the question. Implementations that don't care need not override it. */
    default String promptHuman(String stepId, String message, Hints hints) {
        return promptHuman(stepId, message);
    }
}
