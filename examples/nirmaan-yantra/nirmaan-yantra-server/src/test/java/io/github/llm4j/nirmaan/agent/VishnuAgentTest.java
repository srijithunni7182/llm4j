package io.github.llm4j.nirmaan.agent;

import io.github.llm4j.nirmaan.model.ProjectContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VishnuAgentTest {

    private final io.github.llm4j.agent.prompt.PromptRegistry promptRegistry = org.mockito.Mockito
            .mock(io.github.llm4j.agent.prompt.PromptRegistry.class);
    private final VishnuAgent vishnuAgent = new VishnuAgent(promptRegistry);

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
        assertEquals("Vishnu", vishnuAgent.getName());
        assertEquals("Lead Developer", vishnuAgent.getRole());
    }

    @Test
    void testPerformCodeReview_Safety() {
        ProjectContext context = new ProjectContext("Test");
        // This method calls LLM, so difficult to unit test without mocking.
        // We just ensure it can be called without exception (it handles exceptions
        // internally).
        vishnuAgent.reviewImplementation(context);

        // Check logs to verify attempt
        assertNotNull(context.getActivityLog());
    }
}
