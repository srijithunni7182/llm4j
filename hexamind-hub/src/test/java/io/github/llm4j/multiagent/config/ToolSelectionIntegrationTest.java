package io.github.llm4j.multiagent.config;

import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tools.CachedSearchTool;
import io.github.llm4j.agent.tools.DateTimeTool;
import io.github.llm4j.multiagent.model.AgentParticipant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "google.api.key=test-google-key",
        "google.search.cx=test-cx",
        "serpapi.api.key=test-serp-key"
})
class ToolSelectionIntegrationTest {

    @Autowired
    private List<AgentParticipant> agents;

    @Test
    void testAllAgentsHaveSearchAndDateTimeTools() {
        for (AgentParticipant participant : agents) {
            ReActAgent agent = participant.getAgent();
            java.util.Collection<Tool> tools = agent.getTools();

            // Check for CachedSearchTool (which wraps the fallback chain)
            boolean hasSearch = tools.stream().anyMatch(t -> t instanceof CachedSearchTool);
            // Check for DateTimeTool
            boolean hasDateTime = tools.stream().anyMatch(t -> t instanceof DateTimeTool);

            assertThat(hasSearch).as("Agent %s should have a search tool", participant.getId()).isTrue();
            assertThat(hasDateTime).as("Agent %s should have a date time tool", participant.getId()).isTrue();
        }
    }

    @Test
    void testTimeAwarenessInSystemPrompts() {
        for (AgentParticipant participant : agents) {
            String role = participant.getPersona().getRole();
            List<String> constraints = participant.getPersona().getConstraints();

            boolean hasTimeConstraint = constraints.stream()
                    .anyMatch(c -> c.contains("CURRENT TIME"));

            assertThat(hasTimeConstraint)
                    .as("Agent %s system prompt should contain current time awareness", participant.getId())
                    .isTrue();
        }
    }
}
