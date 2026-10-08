package io.github.llm4j.nirmaan.agent;

import io.github.llm4j.nirmaan.model.ProjectContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RishiAgentTest {

    private final io.github.llm4j.agent.prompt.PromptRegistry promptRegistry = org.mockito.Mockito
            .mock(io.github.llm4j.agent.prompt.PromptRegistry.class);
    private final RishiAgent rishiAgent = new RishiAgent(promptRegistry);

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        io.github.llm4j.agent.prompt.PromptTemplate mockTemplate = org.mockito.Mockito
                .mock(io.github.llm4j.agent.prompt.PromptTemplate.class);
        org.mockito.Mockito.when(mockTemplate.render(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn("Mocked Prompt");
        org.mockito.Mockito.when(promptRegistry.get(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.of(mockTemplate));
    }

    @Test
    void testIdentity() {
        assertEquals("Rishi", rishiAgent.getName());
        assertEquals("Solutions Architect", rishiAgent.getRole());
    }

    @Test
    void testExecute_NoPRD() {
        ProjectContext context = new ProjectContext("TestProject");
        // No artifacts in context, so no PRD.md
        rishiAgent.execute(context);

        // Should log error and fail
        assertTrue(context.getActivityLog().toString().contains("Error: PRD.md not found"));
    }
}
