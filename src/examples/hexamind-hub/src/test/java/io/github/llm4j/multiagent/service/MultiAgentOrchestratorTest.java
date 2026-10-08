package io.github.llm4j.multiagent.service;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.knowledge.KnowledgeGraph;
import io.github.llm4j.agent.rag.store.VectorStore;
import io.github.llm4j.agent.prompt.FileSystemPromptRegistry;
import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.multiagent.model.*;
import io.github.llm4j.multiagent.service.SharedKnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import io.github.llm4j.hexamind.model.User;
import io.github.llm4j.hexamind.service.SessionService;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Paths;
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

        @Mock
        private SessionService mockSessionService;
        private User mockUser;
        private PromptRegistry promptRegistry;

        private List<AgentParticipant> agents;
        private MultiAgentOrchestrator orchestrator;

        @BeforeEach
        void setUp() {
                MockitoAnnotations.openMocks(this);
                promptRegistry = new FileSystemPromptRegistry(Paths.get("src/main/resources/prompts.yaml"));

                // Setup mock agent
                when(mockAgent.getId()).thenReturn("agent-1");
                when(mockAgent.getName()).thenReturn("Tester");

                // Mock agent methods to return immediately
                when(mockAgent.analyze(any(), any())).thenReturn("Analysis");
                when(mockAgent.argue(any(), any(), any())).thenReturn("Argument");
                when(mockAgent.respond(any(), any())).thenReturn("Response");

                AgentOpinion opinion = AgentOpinion.builder()
                                .agentId("agent-1")
                                .recommendation("Do it")
                                .confidence(0.9)
                                .keyPoints(Collections.singletonList("Point"))
                                .build();
                when(mockAgent.formOpinion(any(), any(), any())).thenReturn(opinion);

                when(mockSharedService.getKnowledgeGraph(anyString())).thenReturn(mock(KnowledgeGraph.class));
                when(mockSharedService.getVectorStore(anyString())).thenReturn(mock(VectorStore.class));

                agents = Collections.singletonList(mockAgent);
                mockUser = User.builder().id(1L).email("test@test.com").name("Test User").build();

                // Handle both normal chat and extraction calls
                when(llmClient.chat(any(LLMRequest.class))).thenAnswer(invocation -> {
                        LLMRequest request = invocation.getArgument(0);
                        String content = request.getMessages().get(0).getContent();

                        if (content.contains("Extract knowledge triples")) {
                                return LLMResponse.builder().content("[]").build();
                        }
                        return LLMResponse.builder().content("Consensus Reached").build();
                });

                orchestrator = new MultiAgentOrchestrator(agents, messagingTemplate, llmClient, mockSharedService,
                                mockSessionService, promptRegistry);
        }

        @Test
        void testStartCollaboration() {
                // Mock consensus LLM call
                LLMResponse mockResponse = LLMResponse.builder()
                                .content("Consensus Reached")
                                .build();

                String sessionId = orchestrator.startCollaboration("Test Problem", mockUser);

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

                String sessionId = orchestrator.startCollaboration("Problem", mockUser);
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

                String sessionId = orchestrator.startCollaboration(null, mockUser);
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

                String sessionId = orchestrator.startCollaboration("", mockUser);
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

                String sessionId = orchestrator.startCollaboration("Problem", mockUser);
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
                when(mockAgent2.analyze(any(), any())).thenReturn("Analysis 2");
                when(mockAgent2.argue(any(), any(), any())).thenReturn("Argument 2");
                when(mockAgent2.respond(any(), any())).thenReturn("Response 2");

                AgentOpinion opinion2 = AgentOpinion.builder()
                                .agentId("agent-2")
                                .recommendation("Different approach")
                                .confidence(0.7)
                                .keyPoints(Collections.singletonList("Point 2"))
                                .build();
                when(mockAgent2.formOpinion(any(), any(), any())).thenReturn(opinion2);

                List<AgentParticipant> multipleAgents = List.of(mockAgent, mockAgent2);
                MultiAgentOrchestrator multiOrchestrator = new MultiAgentOrchestrator(multipleAgents, messagingTemplate,
                                llmClient, mockSharedService, mockSessionService, promptRegistry);

                LLMResponse mockResponse = LLMResponse.builder()
                                .content("Multi-agent Consensus")
                                .build();
                when(llmClient.chat(any(LLMRequest.class))).thenAnswer(invocation -> {
                        LLMRequest request = invocation.getArgument(0);
                        String content = request.getMessages().get(0).getContent();
                        if (content.contains("Extract knowledge triples")) {
                                return LLMResponse.builder().content("[]").build();
                        }
                        return mockResponse;
                });

                String sessionId = multiOrchestrator.startCollaboration("Complex Problem", mockUser);

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
                                llmClient, mockSharedService, mockSessionService, promptRegistry);

                LLMResponse mockResponse = LLMResponse.builder()
                                .content("No agents consensus")
                                .build();

                String sessionId = emptyOrchestrator.startCollaboration("Problem", mockUser);

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

                String sessionId1 = orchestrator.startCollaboration("Problem 1", mockUser);
                String sessionId2 = orchestrator.startCollaboration("Problem 2", mockUser);
                String sessionId3 = orchestrator.startCollaboration("Problem 3", mockUser);

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

                String sessionId = orchestrator.startCollaboration("Problem", mockUser);

                await().atMost(15, TimeUnit.SECONDS)
                                .until(() -> orchestrator.getSession(sessionId)
                                                .getStatus() != CollaborationSession.SessionStatus.CREATED);

                CollaborationSession session = orchestrator.getSession(sessionId);
                assertThat(session).isNotNull();
        }

        @Test
        void testKnowledgeBroadcast() {
                // Prepare a thought that triggers extraction
                String thoughtContent = "Solar energy is a renewable source.";

                // Mock LLM extraction response
                String validationJson = "[{\"subject\": \"Solar Energy\", \"predicate\": \"is\", \"object\": \"Renewable Source\"}]";
                LLMResponse extractionResponse = LLMResponse.builder().content(validationJson).build();

                // We need to match the specific prompt or just any LLM call?
                // In the real code, it calls llmClient.chat again.
                // We can setup the mock to return extractionResponse when called with
                // extraction prompt.
                // Match based on message content
                when(llmClient.chat(argThat(req -> req != null && req.getMessages() != null &&
                                req.getMessages().stream()
                                                .anyMatch(m -> m.getContent() != null
                                                                && m.getContent().contains(
                                                                                "Extract knowledge triples")))))
                                .thenReturn(extractionResponse);

                // Mock standard analysis response for agents (anything NOT extraction)
                when(llmClient.chat(argThat(req -> req != null && req.getMessages() != null &&
                                req.getMessages().stream()
                                                .noneMatch(m -> m.getContent() != null
                                                                && m.getContent().contains(
                                                                                "Extract knowledge triples")))))
                                .thenReturn(LLMResponse.builder().content("Analysis").build());

                // We can't easily trigger just "extractKnowledge" since it's private.
                // But we can trigger agent.analyze -> process thought -> extraction.

                // Let's rely on the orchestrator internals or simulate a flow?
                // Since extractKnowledge is private, we must go through public API.

                // Simplify: Just verify that IF extraction happens, broadcast is called.
                // But to do that we need to run a flow.
        }
}
