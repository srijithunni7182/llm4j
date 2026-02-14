package io.github.llm4j.dojo.model;

import io.github.llm4j.agent.persona.AgentPersona;
import java.util.List;

/**
 * Defines a stakeholder in the simulation.
 */
public record StakeholderProfile(
        String id,
        String name,
        String role, // ProductManager, EngineeringManager, etc.
        AgentPersona agentPersona, // The AI persona backing this stakeholder
        String focusArea,
        List<String> hiddenGoals, // Goals the user has to discover (e.g. "Wants a promotion")
        String avatarPath, // Path to generated image
        String currentMood // e.g. "Neutral", "Stressed", "Happy"
) {
    public StakeholderProfile withMood(String newMood) {
        return new StakeholderProfile(id, name, role, agentPersona, focusArea, hiddenGoals, avatarPath, newMood);
    }
}
