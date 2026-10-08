package io.github.llm4j.loom.memory;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.runtime.VariableContext;

/**
 * Pluggable memory engine interface for Loom DSL executions.
 * The MemoryEngine is responsible for context assembly before an agent turn,
 * and memory extraction/storage after an agent turn.
 */
public interface MemoryEngine {

    /**
     * Called before an agent executes to assemble its context briefing.
     *
     * @param agentDef    The definition of the agent about to execute.
     * @param taskIntent  The specific task or prompt for this turn.
     * @param context     The current Loom VariableContext.
     * @return The synthesized context briefing string.
     */
    String assembleContext(AgentDef agentDef, String taskIntent, VariableContext context);

    /**
     * Called after an agent finishes executing to extract and store outcomes.
     *
     * @param agentDef    The agent that just executed.
     * @param taskIntent  The task that was executed.
     * @param result      The output from the agent.
     * @param context     The current Loom VariableContext.
     */
    void storeOutcome(AgentDef agentDef, String taskIntent, AgentResult result, VariableContext context);

    /**
     * A mark of what the engine remembers now, for a rewind to go back to; null when the engine keeps nothing a rewind should undo. A
     * rewind discards the attempt after a checkpoint, so what the agents said in it must not shape the next attempt: carrying values is
     * the way to pass on what was learned.
     */
    default Object mark() {
        return null;
    }

    /** Goes back to what {@link #mark()} returned. */
    default void restore(Object mark) {
    }
}
