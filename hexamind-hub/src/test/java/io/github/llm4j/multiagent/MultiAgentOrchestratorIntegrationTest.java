package io.github.llm4j.multiagent;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.persona.PersonaLibrary;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.multiagent.model.AgentParticipant;
import io.github.llm4j.multiagent.model.CollaborationSession;
import io.github.llm4j.multiagent.service.MultiAgentOrchestrator;
import io.github.llm4j.provider.google.GoogleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.mockito.Mockito.mock;

public class MultiAgentOrchestratorIntegrationTest {

    private MultiAgentOrchestrator orchestrator;
    private final String API_KEY = "AIzaSyC1Kxs2UCzUcxpQFQ6tP918RdGQA3_rt1A";

    @BeforeEach
    void setUp() {
        LLMConfig config = LLMConfig.builder()
                .apiKey(API_KEY)
                .defaultModel("gemini-2.0-flash")
                .build();

        LLMClient client = new DefaultLLMClient(new GoogleProvider(config));

        List<AgentParticipant> agents = List.of(
                createAgent("tech", "Technical Analyst", PersonaLibrary.technicalAnalyst(), client),
                createAgent("business", "Business Consultant", PersonaLibrary.businessConsultant(), client));

        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        orchestrator = new MultiAgentOrchestrator(agents, messagingTemplate, client);
    }

    private AgentParticipant createAgent(String id, String name, io.github.llm4j.agent.persona.AgentPersona persona,
            LLMClient client) {
        ReActAgent agent = ReActAgent.builder()
                .llmClient(client)
                .persona(persona)
                .maxIterations(3)
                .build();

        return new AgentParticipant(id, name, persona, agent, "/images/dummy.png");
    }

    @Test
    void testCollaboration() {
        String problem = "How can I filter out bird sounds I hear in nature?";
        String sessionId = orchestrator.startCollaboration(problem);

        System.out.println("Session started: " + sessionId);

        // Wait for collaboration to complete (since it runs in a thread)
        // In a real test we might want to join the thread or use a synchronous version
        // For debugging, we'll just wait a bit or modify orchestrator to be more
        // testable

        try {
            Thread.sleep(30000); // Wait 30 seconds for some progress
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        CollaborationSession session = orchestrator.getSession(sessionId);
        System.out.println("Session status: " + session.getStatus());
        System.out.println("Thoughts generated: " + session.getThoughts().size());

        if (session.getConsensus() != null) {
            System.out.println("Consensus: " + session.getConsensus().getRecommendation());
        }
    }
}
