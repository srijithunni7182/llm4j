package io.github.llm4j.agent.tool;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.skill.AgentSkill;
import io.github.llm4j.agent.skill.SkillMetadata;
import io.github.llm4j.agent.skill.SkillRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A tool that allows the agent to dynamically search for and read skills from a {@link SkillRegistry}.
 */
public class SkillDiscoveryTool implements Tool {

    private final SkillRegistry registry;

    public SkillDiscoveryTool(SkillRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
    }

    @Override
    public String getName() {
        return "skill_discovery";
    }

    @Override
    public String getDescription() {
        return "A tool to search for new skills and load them into context dynamically. " +
               "Use this when you lack the knowledge to solve a specific task and need domain-specific instructions. " +
               "It supports two actions:\n" +
               "1. search: Provide `action: \"search\"` and `query: \"<search keywords>\"` to browse the registry.\n" +
               "2. read: Provide `action: \"read\"` and `skillId: \"<id>\"` to read the specific skill instructions into your context.";
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String action = (String) arguments.get("action");
        if (action == null || action.isBlank()) {
            return "Error: Missing required argument 'action'. Must be 'search' or 'read'.";
        }

        if ("search".equalsIgnoreCase(action)) {
            String query = (String) arguments.get("query");
            if (query == null) {
                query = ""; // empty query returns top/generic results depending on registry
            }
            try {
                List<SkillMetadata> results = registry.searchSkills(query);
                if (results.isEmpty()) {
                    return "No skills found matching query: '" + query + "'";
                }
                
                String catalog = results.stream()
                    .map(m -> String.format("- ID: %s\n  Name: %s\n  Description: %s", m.id(), m.name(), m.description()))
                    .collect(Collectors.joining("\n\n"));
                
                return "Found the following skills:\n" + catalog + "\n\nUse the 'read' action with the desired skill ID to learn it.";
            } catch (Exception e) {
                return "Error searching skills: " + e.getMessage();
            }
        } else if ("read".equalsIgnoreCase(action)) {
            String skillId = (String) arguments.get("skillId");
            if (skillId == null || skillId.isBlank()) {
                return "Error: Missing required argument 'skillId' for action 'read'.";
            }
            try {
                AgentSkill skill = registry.getSkill(skillId);
                return "Successfully loaded skill '" + skill.getName() + "'. Here are the skill instructions for you to follow:\n\n" + skill.toSystemPromptSection();
            } catch (Exception e) {
                return "Error fetching skill: " + e.getMessage();
            }
        } else {
            return "Error: Unknown action '" + action + "'. Must be 'search' or 'read'.";
        }
    }
}
