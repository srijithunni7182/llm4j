package io.github.llm4j.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.llm4j.agent.skill.AgentSkill;
import io.github.llm4j.agent.skill.SkillMetadata;
import io.github.llm4j.agent.skill.SkillRegistry;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class SkillDiscoveryToolTest {

    @Test
    void testSearchSkills() {
        SkillRegistry mockRegistry = new SkillRegistry() {
            @Override
            public List<SkillMetadata> searchSkills(String query) throws IOException {
                if ("java".equals(query)) {
                    return List.of(new SkillMetadata("java-expert", "Java Expert", "Java coding standard", "test", List.of()));
                }
                return List.of();
            }

            @Override
            public AgentSkill getSkill(String skillId) throws IOException {
                return null;
            }
        };

        SkillDiscoveryTool tool = new SkillDiscoveryTool(mockRegistry);
        
        String result = tool.execute(Map.of("action", "search", "query", "java"));
        assertTrue(result.contains("java-expert"));
        assertTrue(result.contains("Java Expert"));

        String emptyResult = tool.execute(Map.of("action", "search", "query", "unknown"));
        assertTrue(emptyResult.contains("No skills found"));
    }

    @Test
    void testReadSkill() {
        SkillRegistry mockRegistry = new SkillRegistry() {
            @Override
            public List<SkillMetadata> searchSkills(String query) throws IOException {
                return List.of();
            }

            @Override
            public AgentSkill getSkill(String skillId) throws IOException {
                if ("java-expert".equals(skillId)) {
                    return AgentSkill.of("Java Expert", "You are a Java 21 expert.");
                }
                throw new IOException("Not found");
            }
        };

        SkillDiscoveryTool tool = new SkillDiscoveryTool(mockRegistry);
        
        String result = tool.execute(Map.of("action", "read", "skillId", "java-expert"));
        assertTrue(result.contains("Successfully loaded skill 'Java Expert'"));
        assertTrue(result.contains("You are a Java 21 expert."));
        
        String errorResult = tool.execute(Map.of("action", "read", "skillId", "unknown"));
        assertTrue(errorResult.contains("Error fetching skill: Not found"));
    }

    @Test
    void testInvalidAction() {
        SkillDiscoveryTool tool = new SkillDiscoveryTool(new SkillRegistry() {
            @Override
            public List<SkillMetadata> searchSkills(String query) { return List.of(); }
            @Override
            public AgentSkill getSkill(String skillId) { return null; }
        });

        String result = tool.execute(Map.of("action", "delete"));
        assertTrue(result.contains("Unknown action"));
        
        String result2 = tool.execute(Map.of());
        assertTrue(result2.contains("Missing required argument 'action'"));
    }
}
