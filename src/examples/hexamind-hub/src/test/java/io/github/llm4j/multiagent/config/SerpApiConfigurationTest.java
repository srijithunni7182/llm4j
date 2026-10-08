package io.github.llm4j.multiagent.config;

import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.tools.SerpApiSearchTool;
import io.github.llm4j.agent.tools.WebSearchTool;
import io.github.llm4j.multiagent.model.AgentParticipant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
                "google.api.key=test-api-key",
                "google.search.cx=test-search-cx",
                "serpapi.api.key=test-serp-api-key"
})
class SerpApiConfigurationTest {

        @Autowired
        private List<AgentParticipant> agents;

        @Test
        void testAgentsUseSerpApiSearchTool() {
                for (AgentParticipant participant : agents) {
                        ReActAgent agent = (ReActAgent) participant.getAgent();

                        // Check if any tool is SerpApiSearchTool OR CachedSearchTool (which wraps it)
                        boolean hasSerpApiTool = agent.getTools().stream()
                                        .anyMatch(tool -> tool instanceof SerpApiSearchTool
                                                        || tool instanceof io.github.llm4j.agent.tools.CachedSearchTool);

                        boolean hasWebSearchTool = agent.getTools().stream()
                                        .anyMatch(tool -> tool instanceof WebSearchTool);

                        assertThat(hasSerpApiTool)
                                        .as("Agent " + participant.getName() + " should have SerpApiSearchTool")
                                        .isTrue();
                        assertThat(hasWebSearchTool)
                                        .as("Agent " + participant.getName() + " should NOT have WebSearchTool")
                                        .isFalse();
                }
        }
}
