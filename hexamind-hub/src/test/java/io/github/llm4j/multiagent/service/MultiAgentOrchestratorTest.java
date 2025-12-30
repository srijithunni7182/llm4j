package io.github.llm4j.multiagent.service;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.knowledge.KnowledgeGraph;
import io.github.llm4j.agent.rag.store.VectorStore;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.multiagent.model.*;
import io.github.llm4j.multiagent.service.SharedKnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MultiAgentOrchestratorTest {

        @Mock
        private SimpMessagingTemplate messagingTemplate;

        @Mock
        private LLMClient llmClient;

        @Mock
        private AgentParticipant mockAgent;

        @Mock
        private SharedKnowledgeService mockSharedService;

        private List<AgentParticipant> agents;
        private MultiAgentOrchestrator orchestrator;

        @BeforeEach
        void setUp() {
                MockitoAnnotations.openMocks(this);

                // Setup mock agent
                when(mockAgent.getId()).thenReturn("agent-1");
                when(mockAgent.getName()).thenReturn("Tester");

                // Mock agent methods to return immediately
                when(mockAgent.analyze(anyString(), anyString())).thenReturn("Analysis");
                when(mockAgent.argue(anyString(), anyString(), anyString())).thenReturn("Argument");
                when(mockAgent.respond(anyString(), anyString())).thenReturn("Response");

                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("agent-1")
                                .recommendation("Do it")
                                .confidence(0.9)
                                .keyPoints(Collections.singletonList("Point"))
                                .build();
                when(mockAgent.formOpinion(anyString(), anyString(), any())).thenReturn(opinion);

                when(mockSharedService.getKnowledgeGraph(anyString())).thenReturn(mock(KnowledgeGraph.class));
                when(mockSharedService.getVectorStore(anyString())).thenReturn(mock(VectorStore.class));

                agents = Collections.singletonList(mockAgent);
                orchestrator = new MultiAgentOrchestrator(agents, messagingTemplate, llmClient, mockSharedService);
        }

        @Test
        void testStartCollaboration() {
                // Mock consensus LLM call
                LLMResponse mockResponse = LLMResponse.builder()
                                .content("Consensus Reached")
                                .build();
                when(llmClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

                String sessionId = orchestrator.startCollaboration("Test Problem");

                assertThat(sessionId).isNotNull();
                CollaborationSession session = orchestrator.getSession(sessionId);
                assertThat(session).isNotNull();
                assertThat(session.getProblem()).isEqualTo("Test Problem");

                // Wait for collaboration to complete
                await().atMost(15, TimeUnit.SECONDS)
                                .until(() -> session.getStatus() == CollaborationSession.SessionStatus.COMPLETED ||
                                                session.getStatus() == CollaborationSession.SessionStatus.FAILED);

                if (session.getStatus() == CollaborationSession.SessionStatus.FAILED) {
                        assertThat(session.getStatus()).isNotEqualTo(CollaborationSession.SessionStatus.FAILED);
                }

                assertThat(session.getStatus()).isEqualTo(CollaborationSession.SessionStatus.COMPLETED);
                assertThat(session.getConsensus()).isNotNull();
                assertThat(session.getConsensus().getRecommendation()).isEqualTo("Consensus Reached");

                // Verify agent interactions
                verify(mockAgent, atLeastOnce()).analyze(anyString(), anyString());
                verify(mockAgent, atLeastOnce()).argue(anyString(), anyString(), anyString());
                verify(messagingTemplate, atLeastOnce()).convertAndSend(anyString(), any(CollaborationSession.class));
        }

        @Test
        void testProcessFeedback() {
                LLMResponse mockResponse = LLMResponse.builder()
                                .content("Consensus Reached")
                                .build();
                when(llmClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

                String sessionId = orchestrator.startCollaboration("Problem");
                await().atMost(15, TimeUnit.SECONDS).until(
                                () -> orchestrator.getSession(sessionId)
                                                .getStatus() == CollaborationSession.SessionStatus.COMPLETED);

                clearInvocations(mockAgent, messagingTemplate);

                orchestrator.processFeedback(sessionId, "Please refine");

                CollaborationSession session = orchestrator.getSession(sessionId);

                await().atMost(15, TimeUnit.SECONDS)
                                .until(() -> session.getStatus() == CollaborationSession.SessionStatus.COMPLETED
                                                && session.getCurrentRound() > 5);

                verify(mockAgent, atLeastOnce()).respond(anyString(), contains("USER FEEDBACK"));
        }

        @Test
        void testGetSessionWithInvalidId() {
                CollaborationSession session = orchestrator.getSession("non-existent-id");
                assertThat(session).isNull();
        }

        @Test
        void testStartCollaborationWithNullProblem() {
                LLMResponse mockResponse = LLMResponse.builder()
                                .content("Consensus")
                                .build();
                when(llmClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

                String sessionId = orchestrator.startCollaboration(null);
                assertThat(sessionId).isNotNull();

                CollaborationSession session = orchestrator.getSession(sessionId);
                assertThat(session).isNotNull();
                assertThat(session.getProblem()).isNull();
        }

        @Test
        void testStartCollaborationWithEmptyProblem() {
                LLMResponse mockResponse = LLMResponse.builder()
                                .content("Consensus")
                                .build();
                when(llmClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

                String sessionId = orchestrator.startCollaboration("");
                assertThat(sessionId).isNotNull();

                CollaborationSession session = orchestrator.getSession(sessionId);
                assertThat(session).isNotNull();
                assertThat(session.getProblem()).isEmpty();
        }

        @Test
        void testProcessFeedbackWithInvalidSessionId() {
                // Should handle gracefully without throwing exception
                orchestrator.processFeedback("invalid-session", "feedback");
                // No exception means test passes
        }

        @Test
        void testProcessFeedbackWithNullFeedback() {
                LLMResponse mockResponse = LLMResponse.builder()
                                .content("Consensus")
                                .build();
                when(llmClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

                String sessionId = orchestrator.startCollaboration("Problem");
                await().atMost(15, TimeUnit.SECONDS).until(
                                () -> orchestrator.getSession(sessionId)
                                                .getStatus() == CollaborationSession.SessionStatus.COMPLETED);

                // Should handle null feedback gracefully
                orchestrator.processFeedback(sessionId, null);
                // No exception means test passes
        }

        @Test
        void testMultipleAgentsCollaboration() {
                AgentParticipant mockAgent2 = mock(AgentParticipant.class);
                when(mockAgent2.getId()).thenReturn("agent-2");
                when(mockAgent2.getName()).thenReturn("Tester2");
                when(mockAgent2.analyze(anyString(), anyString())).thenReturn("Analysis 2");
                when(mockAgent2.argue(anyString(), anyString(), anyString())).thenReturn("Argument 2");
                when(mockAgent2.respond(anyString(), anyString())).thenReturn("Response 2");

                AgentOpinion opinion2 = AgentOpinion.builder()
                                .agentId("agent-2")
                                .recommendation("Different approach")
                                .confidence(0.7)
                                .keyPoints(Collections.singletonList("Point 2"))
                                .build();
                when(mockAgent2.formOpinion(anyString(), anyString(), any())).thenReturn(opinion2);

                List<AgentParticipant> multipleAgents = List.of(mockAgent, mockAgent2);
                MultiAgentOrchestrator multiOrchestrator = new MultiAgentOrchestrator(multipleAgents, messagingTemplate,
                                llmClient, mockSharedService);

                LLMResponse mockResponse = LLMResponse.builder()
                                .content("Multi-agent Consensus")
                                .build();
                when(llmClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

                String sessionId = multiOrchestrator.startCollaboration("Complex Problem");

                await().atMost(20, TimeUnit.SECONDS)
                                .until(() -> multiOrchestrator.getSession(sessionId)
                                                .getStatus() == CollaborationSession.SessionStatus.COMPLETED);

                CollaborationSession session = multiOrchestrator.getSession(sessionId);
                assertThat(session.getConsensus()).isNotNull();
                assertThat(session.getConsensus().getAgentOpinions()).hasSize(2);

                verify(mockAgent, atLeastOnce()).analyze(anyString(), anyString());
                verify(mockAgent2, atLeastOnce()).analyze(anyString(), anyString());
        }

        @Test
        void testEmptyAgentsList() {
                List<AgentParticipant> emptyAgents = Collections.emptyList();
                MultiAgentOrchestrator emptyOrchestrator = new MultiAgentOrchestrator(emptyAgents, messagingTemplate,
                                llmClient, mockSharedService);

                LLMResponse mockResponse = LLMResponse.builder()
                                .content("No agents consensus")
                                .build();
                when(llmClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

                String sessionId = emptyOrchestrator.startCollaboration("Problem");

                await().atMost(15, TimeUnit.SECONDS)
                                .until(() -> emptyOrchestrator.getSession(sessionId)
                                                .getStatus() == CollaborationSession.SessionStatus.COMPLETED ||
                                                emptyOrchestrator.getSession(sessionId)
                                                                .getStatus() == CollaborationSession.SessionStatus.FAILED);

                CollaborationSession session = emptyOrchestrator.getSession(sessionId);
                assertThat(session).isNotNull();
        }

        @Test
        void testConcurrentSessionCreation() {
                LLMResponse mockResponse = LLMResponse.builder()
                                .content("Consensus")
                                .build();
                when(llmClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

                String sessionId1 = orchestrator.startCollaboration("Problem 1");
                String sessionId2 = orchestrator.startCollaboration("Problem 2");
                String sessionId3 = orchestrator.startCollaboration("Problem 3");

                assertThat(sessionId1).isNotEqualTo(sessionId2);
                assertThat(sessionId2).isNotEqualTo(sessionId3);
                assertThat(sessionId1).isNotEqualTo(sessionId3);

                assertThat(orchestrator.getSession(sessionId1)).isNotNull();
                assertThat(orchestrator.getSession(sessionId2)).isNotNull();
                assertThat(orchestrator.getSession(sessionId3)).isNotNull();
        }

        @Test
        void testAgentFailureDuringAnalysis() {
                when(mockAgent.analyze(anyString(), anyString())).thenThrow(new RuntimeException("Agent failure"));

                LLMResponse mockResponse = LLMResponse.builder()
                                .content("Partial consensus")
                                .build();
                when(llmClient.chat(any(LLMRequest.class))).thenReturn(mockResponse);

                String sessionId = orchestrator.startCollaboration("Problem");

                await().atMost(15, TimeUnit.SECONDS)
                                .until(() -> orchestrator.getSession(sessionId)
                                                .getStatus() != CollaborationSession.SessionStatus.CREATED);

                CollaborationSession session = orchestrator.getSession(sessionId);
                assertThat(session).isNotNull();
        }
}
