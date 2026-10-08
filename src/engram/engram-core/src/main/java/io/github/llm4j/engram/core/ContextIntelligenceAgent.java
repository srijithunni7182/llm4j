package io.github.llm4j.engram.core;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.engram.core.models.ContextBriefing;
import io.github.llm4j.engram.core.models.MemoryEvent;
import io.github.llm4j.engram.core.models.MemoryObject;
import io.github.llm4j.engram.core.models.ScoredMemory;

import java.util.List;

/**
 * The mind of the Engram memory system.
 * Responsible for actively extracting memories from outcomes and synthesizing context briefings.
 */
public interface ContextIntelligenceAgent {

    /**
     * Analyzes the outcome of an agent's task and extracts discrete memory events/facts.
     *
     * @param agentDef The agent that executed the task.
     * @param taskIntent The original intent of the task.
     * @param result The outcome of the task.
     * @return A list of extracted MemoryEvents.
     */
    List<MemoryEvent> extractMemories(AgentDef agentDef, String taskIntent, AgentResult result);

    /**
     * Synthesizes a tailored context briefing for a new task using the raw candidate memories.
     *
     * @param taskIntent The intent of the new task to be executed.
     * @param candidates The top candidate memories retrieved from the store.
     * @return A compiled ContextBriefing ready to be injected into the prompt.
     */
    ContextBriefing synthesizeBriefing(String taskIntent, List<ScoredMemory> candidates);

    /**
     * Retrospectively reviews an agent's execution to learn new strategies or prune bad memories.
     *
     * @param agentDef The agent that executed the task.
     * @param taskIntent The intent of the task.
     * @param briefing The context briefing that was given to the agent.
     * @param result The outcome of the task.
     * @return An IntrospectionResult containing extracted feedback and a list of bad memories to prune.
     */
    io.github.llm4j.engram.core.models.IntrospectionResult introspect(AgentDef agentDef, String taskIntent, ContextBriefing briefing, AgentResult result);
}
