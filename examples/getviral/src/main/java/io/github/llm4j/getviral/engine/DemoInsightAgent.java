package io.github.llm4j.getviral.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.engram.core.TemplateContextIntelligenceAgent;
import io.github.llm4j.engram.core.models.ContextBriefing;
import io.github.llm4j.engram.core.models.MemoryEvent;
import io.github.llm4j.engram.core.models.ScoredMemory;
import io.github.llm4j.loom.ast.AgentDef;
import java.util.ArrayList;
import java.util.List;

/**
 * A no-LLM Context Intelligence Agent for demo mode: extracts short, human-readable facts from the
 * Strategist's and Critic's structured outputs instead of storing raw transcripts. (With a real
 * model, GetViral uses Engram's {@code LLMContextIntelligenceAgent}.)
 */
public class DemoInsightAgent extends TemplateContextIntelligenceAgent {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public List<MemoryEvent> extractMemories(AgentDef agentDef, String taskIntent, AgentResult result) {
        List<MemoryEvent> events = new ArrayList<>();
        JsonNode json;
        try {
            json = JSON.readTree(result.getFinalAnswer());
        } catch (Exception e) {
            return super.extractMemories(agentDef, taskIntent, result);
        }
        String idea = line(taskIntent, "IDEA:");
        if ("Strategist".equals(agentDef.getName()) && json.hasNonNull("angle")) {
            events.add(new MemoryEvent("For \"" + idea + "\" the chosen angle was: " + json.get("angle").asText(),
                    "OUTCOME", 0.6, null, List.of("strategy")));
        }
        if ("ViralityCritic".equals(agentDef.getName()) && json.hasNonNull("headline")) {
            events.add(new MemoryEvent("Critic lesson (" + json.path("score").asText("?") + "/10): "
                    + json.get("headline").asText(), "PERFORMANCE_FEEDBACK", 0.7, null, List.of("critic")));
        }
        return events;
    }

    @Override
    public ContextBriefing synthesizeBriefing(String taskIntent, List<ScoredMemory> candidates) {
        StringBuilder briefing = new StringBuilder("Current Task: ").append(taskIntent);
        if (!candidates.isEmpty()) {
            briefing.append("\n\nWhat GetViral remembers about this creator (Engram):\n");
            candidates.stream().limit(6).forEach(sm -> briefing.append("  • ").append(sm.memory().getContent()).append('\n'));
        }
        return new ContextBriefing(briefing.toString(), false);
    }

    static String line(String text, String label) {
        for (String l : text.split("\n")) {
            int i = l.indexOf(label);
            if (i >= 0) return l.substring(i + label.length()).strip();
        }
        return "";
    }
}
