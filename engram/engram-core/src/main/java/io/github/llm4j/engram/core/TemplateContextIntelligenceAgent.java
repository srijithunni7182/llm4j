package io.github.llm4j.engram.core;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.engram.core.models.ContextBriefing;
import io.github.llm4j.engram.core.models.MemoryEvent;
import io.github.llm4j.engram.core.models.ScoredMemory;

import java.util.ArrayList;
import java.util.List;

public class TemplateContextIntelligenceAgent implements ContextIntelligenceAgent {

    @Override
    public List<MemoryEvent> extractMemories(AgentDef agentDef, String taskIntent, AgentResult result) {
        List<MemoryEvent> events = new ArrayList<>();
        if (result != null && result.getFinalAnswer() != null) {
            String content = "Agent " + agentDef.getName() + " completed task [" + taskIntent + "] with outcome: " + result.getFinalAnswer();
            events.add(new MemoryEvent(content, "EPISODIC", 0.5, null, new ArrayList<>()));
        }
        return events;
    }

    @Override
    public ContextBriefing synthesizeBriefing(String taskIntent, List<ScoredMemory> candidates) {
        StringBuilder briefing = new StringBuilder();
        briefing.append("Current Task: ").append(taskIntent).append("\n\n");
        
        if (!candidates.isEmpty()) {
            briefing.append("Context Briefing (Synthesized from relevant memory):\n");
            for (ScoredMemory sm : candidates) {
                briefing.append("  • ").append(sm.memory().getContent()).append("\n");
            }
        }
        
        return new ContextBriefing(briefing.toString(), false);
    }

    @Override
    public io.github.llm4j.engram.core.models.IntrospectionResult introspect(AgentDef agentDef, String taskIntent, ContextBriefing briefing, AgentResult result) {
        // Fallback has no reflection capability
        return new io.github.llm4j.engram.core.models.IntrospectionResult(new ArrayList<>(), new ArrayList<>());
    }
}
