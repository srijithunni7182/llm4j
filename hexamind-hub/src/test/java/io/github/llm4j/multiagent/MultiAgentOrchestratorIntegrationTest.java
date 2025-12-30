package io.github.llm4j.multiagent;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.persona.PersonaLibrary;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.multiagent.model.AgentParticipant;
import io.github.llm4j.multiagent.model.CollaborationSession;
import io.github.llm4j.multiagent.service.MultiAgentOrchestrator;
import io.github.llm4j.agent.knowledge.KnowledgeGraph;
import io.github.llm4j.agent.rag.store.VectorStore;
import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.multiagent.service.SharedKnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test for MultiAgentOrchestrator with mocked LLM client.
 * This avoids making real API calls while still testing the full collaboration
 * flow.
 */
public class MultiAgentOrchestratorIntegrationTest {

        private MultiAgentOrchestrator orchestrator;
        private LLMClient mockLLMClient;
        private SharedKnowledgeService mockSharedService;

        @BeforeEach
        void setUp() {
                // Use mocked LLM client to avoid real API calls
                mockLLMClient = mock(LLMClient.class);
                mockSharedService = mock(SharedKnowledgeService.class);

                // Mock shared service responses
                when(mockSharedService.getKnowledgeGraph(anyString())).thenReturn(mock(KnowledgeGraph.class));
                when(mockSharedService.getVectorStore(anyString())).thenReturn(mock(VectorStore.class));
                EmbeddingProvider mockEmbeddingProvider = mock(EmbeddingProvider.class);
                when(mockEmbeddingProvider.embed(anyString())).thenReturn(new float[1536]);
                when(mockSharedService.getEmbeddingProvider()).thenReturn(mockEmbeddingProvider);

                // Mock LLM responses
                LLMResponse mockResponse = LLMResponse.builder()
                                .content("Final Answer: This is a comprehensive consensus based on all agent inputs.")
                                .build();
                when(mockLLMClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

                List<AgentParticipant> agents = List.of(
                                createMockAgent("tech", "Technical Analyst", PersonaLibrary.technicalAnalyst()),
                                createMockAgent("business", "Business Consultant",
                                                PersonaLibrary.businessConsultant()));

                SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
                orchestrator = new MultiAgentOrchestrator(agents, messagingTemplate, mockLLMClient, mockSharedService);
        }

        private AgentParticipant createMockAgent(String id, String name,
                        io.github.llm4j.agent.persona.AgentPersona persona) {
                ReActAgent agent = ReActAgent.builder()
                                .llmClient(mockLLMClient)
                                .persona(persona)
                                .maxIterations(3)
                                .build();

                ReActAgent.Builder mockBuilder = ReActAgent.builder();

                // We need to ensure toBuilder doesn't cause issues if called
                // Since we are building a real agent here (partially with mock LLM),
                // it should work fine without mocking toBuilder unless we spy on it.
                // But AgentParticipant constructor now needs sharedService.

                return new AgentParticipant(id, name, persona, agent, "/images/dummy.png", mockSharedService);
        }

        @Test
        void testCollaborationFlow() {
                String problem = "Should we invest in renewable energy infrastructure?";
                String sessionId = orchestrator.startCollaboration(problem);

                assertThat(sessionId).isNotNull();

                // Wait for collaboration to complete
                await().atMost(30, TimeUnit.SECONDS)
                                .until(() -> {
                                        CollaborationSession session = orchestrator.getSession(sessionId);
                                        return session.getStatus() == CollaborationSession.SessionStatus.COMPLETED ||
                                                        session.getStatus() == CollaborationSession.SessionStatus.FAILED;
                                });

                CollaborationSession session = orchestrator.getSession(sessionId);

                assertThat(session).isNotNull();
                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.COMPLETED);
                assertThat(session.getProblem()).isEqualTo(problem);
                assertThat(session.getThoughts()).isNotEmpty();
                assertThat(session.getConsensus()).isNotNull();
                assertThat(session.getConsensus().getRecommendation()).isNotNull();
        }

        @Test
        void testFeedbackAndRefinement() {
                String problem = "How can we improve customer satisfaction?";
                String sessionId = orchestrator.startCollaboration(problem);

                // Wait for initial collaboration
                await().atMost(30, TimeUnit.SECONDS)
                                .until(() -> orchestrator.getSession(sessionId)
                                                .getStatus() == CollaborationSession.SessionStatus.COMPLETED);

                CollaborationSession initialSession = orchestrator.getSession(sessionId);
                int initialRounds = initialSession.getCurrentRound();

                // Submit feedback
                orchestrator.processFeedback(sessionId, "Please focus more on digital solutions");

                // Wait for refinement
                await().atMost(30, TimeUnit.SECONDS)
                                .until(() -> {
                                        CollaborationSession session = orchestrator.getSession(sessionId);
                                        return session.getStatus() == CollaborationSession.SessionStatus.COMPLETED &&
                                                        session.getCurrentRound() > initialRounds;
                                });

                CollaborationSession refinedSession = orchestrator.getSession(sessionId);

                assertThat(refinedSession.getCurrentRound()).isGreaterThan(initialRounds);
                assertThat(refinedSession.getStatus()).isEqualTo(CollaborationSession.SessionStatus.COMPLETED);
        }

        @Test
        void testMultipleConcurrentSessions() {
                String problem1 = "Problem 1";
                String problem2 = "Problem 2";
                String problem3 = "Problem 3";

                String sessionId1 = orchestrator.startCollaboration(problem1);
                String sessionId2 = orchestrator.startCollaboration(problem2);
                String sessionId3 = orchestrator.startCollaboration(problem3);

                assertThat(sessionId1).isNotEqualTo(sessionId2);
                assertThat(sessionId2).isNotEqualTo(sessionId3);

                // Wait for all to complete
                await().atMost(45, TimeUnit.SECONDS)
                                .until(() -> {
                                        CollaborationSession s1 = orchestrator.getSession(sessionId1);
                                        CollaborationSession s2 = orchestrator.getSession(sessionId2);
                                        CollaborationSession s3 = orchestrator.getSession(sessionId3);
                                        return s1.getStatus() == CollaborationSession.SessionStatus.COMPLETED &&
                                                        s2.getStatus() == CollaborationSession.SessionStatus.COMPLETED
                                                        &&
                                                        s3.getStatus() == CollaborationSession.SessionStatus.COMPLETED;
                                });

                assertThat(orchestrator.getSession(sessionId1).getStatus())
                                .isEqualTo(CollaborationSession.SessionStatus.COMPLETED);
                assertThat(orchestrator.getSession(sessionId2).getStatus())
                                .isEqualTo(CollaborationSession.SessionStatus.COMPLETED);
                assertThat(orchestrator.getSession(sessionId3).getStatus())
                                .isEqualTo(CollaborationSession.SessionStatus.COMPLETED);
        }

        @Test
        void testSessionRetrieval() {
                String problem = "Test problem";
                String sessionId = orchestrator.startCollaboration(problem);

                CollaborationSession session = orchestrator.getSession(sessionId);

                assertThat(session).isNotNull();
                assertThat(session.getSessionId()).isEqualTo(sessionId);
                assertThat(session.getProblem()).isEqualTo(problem);
        }

        @Test
        void testNonExistentSession() {
                CollaborationSession session = orchestrator.getSession("non-existent-id");
                assertThat(session).isNull();
        }

        @Test
        void testConsensusBuilding() {
                String problem = "What is the best approach for digital transformation?";
                String sessionId = orchestrator.startCollaboration(problem);

                await().atMost(30, TimeUnit.SECONDS)
                                .until(() -> orchestrator.getSession(sessionId)
                                                .getStatus() == CollaborationSession.SessionStatus.COMPLETED);

                CollaborationSession session = orchestrator.getSession(sessionId);

                assertThat(session.getConsensus()).isNotNull();
                assertThat(session.getConsensus().getRecommendation()).isNotEmpty();
                assertThat(session.getConsensus().getAgreementScore()).isBetween(0.0, 1.0);
                assertThat(session.getConsensus().getAgentOpinions()).isNotEmpty();
        }
}
