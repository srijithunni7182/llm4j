package io.github.llm4j.multiagent.service;

import io.github.llm4j.agent.prompt.PromptRegistry;
import io.github.llm4j.multiagent.model.*;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Orchestrates multi-agent collaboration sessions.
 */
@Service
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);

    private final Map<String, CollaborationSession> sessions = new ConcurrentHashMap<>();
    private final List<AgentParticipant> agents;
    private final SimpMessagingTemplate messagingTemplate;
    private final io.github.llm4j.LLMClient llmClient;
    private final SharedKnowledgeService sharedKnowledgeService;
    private final io.github.llm4j.hexamind.service.SessionService sessionService;
    private final PromptRegistry promptRegistry;

    public MultiAgentOrchestrator(List<AgentParticipant> agents,
            SimpMessagingTemplate messagingTemplate,
            io.github.llm4j.LLMClient llmClient,
            SharedKnowledgeService sharedKnowledgeService,
            io.github.llm4j.hexamind.service.SessionService sessionService,
            PromptRegistry promptRegistry) {
        this.agents = agents;
        this.messagingTemplate = messagingTemplate;
        this.llmClient = llmClient;
        this.sharedKnowledgeService = sharedKnowledgeService;
        this.sessionService = sessionService;
        this.promptRegistry = promptRegistry;
    }

    /**
     * Start a new collaboration session.
     */
    public String startCollaboration(String problem, io.github.llm4j.hexamind.model.User user) {
        String sessionId = UUID.randomUUID().toString();

        // Persist session
        sessionService.createSession(user, sessionId, problem);

        CollaborationSession session = new CollaborationSession(
                sessionId,
                problem,
                CollaborationSession.SessionStatus.CREATED,
                Instant.now(),
                null, // completedAt
                new ArrayList<>(), // thoughts
                null, // consensus
                0, // currentRound
                3, // totalRounds
                new ConcurrentHashMap<>() // stats
        );

        sessions.put(sessionId, session);

        // Start collaboration in background thread
        new Thread(() -> conductCollaboration(sessionId)).start();

        return sessionId;
    }

    /**
     * Conduct the full collaboration process.
     */
    private void conductCollaboration(String sessionId) {
        try {
            CollaborationSession session = sessions.get(sessionId);

            // Round 1: Initial Analysis
            session.setStatus(CollaborationSession.SessionStatus.ANALYZING);
            session.setCurrentRound(1);
            session.setTotalRounds(5);
            broadcastSessionUpdate(sessionId, session);

            conductAnalysisRound(sessionId);

            // Round 2: Arguments
            session.setStatus(CollaborationSession.SessionStatus.DEBATING);
            session.setCurrentRound(2);
            broadcastSessionUpdate(sessionId, session);

            conductArgumentRound(sessionId);

            // Round 3: Critique
            session.setCurrentRound(3);
            broadcastSessionUpdate(sessionId, session);

            conductCritiqueRound(sessionId);

            // Round 4: Rebuttal
            session.setCurrentRound(4);
            broadcastSessionUpdate(sessionId, session);

            conductRebuttalRound(sessionId);

            // Round 5: Final Refinement
            session.setCurrentRound(5);
            broadcastSessionUpdate(sessionId, session);

            conductResponseRound(sessionId);

            // Build Consensus
            session.setStatus(CollaborationSession.SessionStatus.BUILDING_CONSENSUS);
            broadcastSessionUpdate(sessionId, session);

            Consensus consensus = buildConsensus(sessionId);
            session.setConsensus(consensus);

            // Complete
            session.setStatus(CollaborationSession.SessionStatus.COMPLETED);
            session.setCompletedAt(Instant.now());
            broadcastSessionUpdate(sessionId, session);
            broadcastConsensus(sessionId, consensus);

            // Archive knowledge to H2 for memory efficiency
            sharedKnowledgeService.archiveSession(sessionId);

        } catch (Exception e) {
            log.error("Error in collaboration session {}", sessionId, e);
            CollaborationSession session = sessions.get(sessionId);
            session.setStatus(CollaborationSession.SessionStatus.FAILED);
            broadcastSessionUpdate(sessionId, session);
        }
    }

    /**
     * Process user feedback and trigger a refinement round.
     */
    public void processFeedback(String sessionId, String feedback) {
        log.info("Processing feedback for session {}: {}", sessionId, feedback);
        CollaborationSession session = sessions.get(sessionId);
        if (session == null)
            return;

        // Load session knowledge if it was archived
        sharedKnowledgeService.loadSession(sessionId);

        // Start refinement in background thread
        new Thread(() -> conductRefinement(sessionId, feedback)).start();
    }

    private void conductRefinement(String sessionId, String feedback) {
        try {
            CollaborationSession session = sessions.get(sessionId);

            // Set state to REFINING
            session.setStatus(CollaborationSession.SessionStatus.REFINING);
            session.setCurrentRound(session.getCurrentRound() + 1);
            session.setTotalRounds(session.getTotalRounds() + 1);
            broadcastSessionUpdate(sessionId, session);

            // Conduct the refinement round
            conductRefinementRound(sessionId, feedback);

            // Rebuild Consensus
            session.setStatus(CollaborationSession.SessionStatus.BUILDING_CONSENSUS);
            broadcastSessionUpdate(sessionId, session);

            Consensus consensus = buildConsensus(sessionId);
            session.setConsensus(consensus);

            // Complete Refinement
            session.setStatus(CollaborationSession.SessionStatus.COMPLETED);
            broadcastSessionUpdate(sessionId, session);
            broadcastConsensus(sessionId, consensus);

            // Re-archive
            sharedKnowledgeService.archiveSession(sessionId);

        } catch (Exception e) {
            log.error("Error during refinement in session {}", sessionId, e);
            CollaborationSession session = sessions.get(sessionId);
            session.setStatus(CollaborationSession.SessionStatus.FAILED);
            broadcastSessionUpdate(sessionId, session);
        }
    }

    /**
     * Agents refine their analysis based on user feedback.
     */
    private void conductRefinementRound(String sessionId, String feedback) {
        CollaborationSession session = sessions.get(sessionId);

        for (AgentParticipant agent : getShuffledAgents()) {
            log.info("Agent {} refining based on feedback", agent.getName());
            session.incrementStat("llm_calls");

            String refinementPrompt = promptRegistry.get("orch_refinement_instruction").orElseThrow()
                    .render(Map.of("feedback", feedback != null ? feedback : ""));

            String refinement = agent.respond(sessionId, refinementPrompt);

            broadcastBurstThoughts(sessionId, agent, refinement, AgentThought.ThoughtType.REFINEMENT, 0.9);

            sleep(1000);
        }
    }

    private void conductAnalysisRound(String sessionId) {
        CollaborationSession session = sessions.get(sessionId);

        // Define a strict fact-checking instruction for Round 1
        String factCheckInstruction = "\n\n" + promptRegistry.get("orch_fact_check_instruction").orElseThrow().render();

        for (AgentParticipant agent : getShuffledAgents()) {
            log.info("Agent {} analyzing problem with fact-check instruction", agent.getName());
            session.incrementStat("llm_calls");

            String analysis = agent.analyze(sessionId, session.getProblem() + factCheckInstruction);

            broadcastBurstThoughts(sessionId, agent, analysis, AgentThought.ThoughtType.ANALYSIS, 0.7);
        }
    }

    /**
     * Round 2: Each agent presents their main argument.
     */
    private void conductArgumentRound(String sessionId) {
        CollaborationSession session = sessions.get(sessionId);

        // Build context from previous round
        String context = buildContext(session.getThoughts());

        for (AgentParticipant agent : getShuffledAgents()) {
            log.info("Agent {} presenting argument", agent.getName());
            session.incrementStat("llm_calls");

            // Assuming agent.argue() handles argument generation structure, we pass the
            // basic instruction?
            // Wait, agent.argue takes 'context'. We append the instruction to context or
            // problem?
            // In original code: agent.argue(sessionId, session.getProblem(), context +
            // "\n\nKeep your response...")
            // The instruction "Keep your response conversational..." is common.
            // Let's assume agent.argue() uses 'agent_argue' template which includes "Keep
            // conversational"?
            // Checking prompts.yaml: agent_argue does NOT include "Keep your response
            // conversational".
            // Adding it manually for now as per original code pattern, or should update
            // template?
            // The original code appended it to 'context'.
            // Let's rely on the template for structure, but 'agent_argue' template seems
            // self-contained.
            // I'll adhere to the new 'agent_argue' template logic which simplifies
            // arguments.

            String argument = agent.argue(sessionId, session.getProblem(), context);

            broadcastBurstThoughts(sessionId, agent, argument, AgentThought.ThoughtType.ARGUMENT, 0.8);
        }
    }

    /**
     * Round 3: Agents critique each other's arguments.
     */
    private void conductCritiqueRound(String sessionId) {
        CollaborationSession session = sessions.get(sessionId);

        for (AgentParticipant agent : getShuffledAgents()) {
            log.info("Agent {} critiquing others", agent.getName());
            session.incrementStat("llm_calls");

            // Get other agents' arguments
            String otherArguments = getOtherAgentsArguments(agent, session.getThoughts());

            String critiquePrompt = promptRegistry.get("orch_critique_instruction").orElseThrow()
                    .render(Map.of("arguments", otherArguments != null ? otherArguments : ""));

            String critique = agent.respond(sessionId, critiquePrompt);

            broadcastBurstThoughts(sessionId, agent, critique, AgentThought.ThoughtType.CRITIQUE, 0.8);
        }
    }

    /**
     * Round 4: Agents rebut the critiques received.
     */
    private void conductRebuttalRound(String sessionId) {
        CollaborationSession session = sessions.get(sessionId);

        for (AgentParticipant agent : getShuffledAgents()) {
            log.info("Agent {} rebutting critiques", agent.getName());
            session.incrementStat("llm_calls");

            // Get critiques directed at or related to this agent's argument
            String relevantThoughts = session.getThoughts().stream()
                    .filter(t -> t.getType() == AgentThought.ThoughtType.CRITIQUE)
                    .map(t -> t.getAgentName() + ": " + t.getContent())
                    .collect(Collectors.joining("\n\n"));

            String rebuttalPrompt = promptRegistry.get("orch_rebuttal_instruction").orElseThrow()
                    .render(Map.of("critiques", relevantThoughts != null ? relevantThoughts : ""));

            String rebuttal = agent.respond(sessionId, rebuttalPrompt);

            broadcastBurstThoughts(sessionId, agent, rebuttal, AgentThought.ThoughtType.REBUTTAL, 0.85);
        }
    }

    /**
     * Round 5: Agents respond to each other's rebuttals and finalize.
     */
    private void conductResponseRound(String sessionId) {
        CollaborationSession session = sessions.get(sessionId);

        for (AgentParticipant agent : getShuffledAgents()) {
            log.info("Agent {} responding to others", agent.getName());
            session.incrementStat("llm_calls");

            // Get other agents' arguments
            String otherArguments = getOtherAgentsArguments(agent, session.getThoughts());

            String responsePrompt = promptRegistry.get("orch_response_instruction").orElseThrow()
                    .render(Map.of("arguments", otherArguments != null ? otherArguments : ""));

            String response = agent.respond(sessionId, responsePrompt);

            broadcastBurstThoughts(sessionId, agent, response, AgentThought.ThoughtType.COUNTER_ARGUMENT, 0.75);
        }
    }

    /**
     * Build consensus from all agent opinions.
     */
    private Consensus buildConsensus(String sessionId) {
        CollaborationSession session = sessions.get(sessionId);

        // Collect final opinions from all agents
        Map<String, AgentOpinion> opinions = new HashMap<>();
        for (AgentParticipant agent : getShuffledAgents()) {
            AgentOpinion opinion = agent.formOpinion(sessionId, session.getProblem(), session.getThoughts());
            session.incrementStat("llm_calls");
            opinions.put(agent.getId(), opinion);
        }

        // Calculate agreement score (simplified)
        double agreementScore = calculateAgreementScore(opinions.values());

        // Synthesize recommendation
        String recommendation = synthesizeRecommendation(session, opinions.values());

        // Extract key points
        List<String> keyPoints = extractKeyPoints(opinions.values());

        return new Consensus(
                recommendation,
                agreementScore,
                opinions,
                keyPoints,
                new ArrayList<>(), // considerations
                "Based on collaborative analysis from " + agents.size() + " expert perspectives");
    }

    private String buildContext(List<AgentThought> thoughts) {
        return thoughts.stream()
                .map(t -> String.format("%s (%s): %s",
                        t.getAgentName(), t.getType(), t.getContent()))
                .collect(Collectors.joining("\n\n"));
    }

    private String getOtherAgentsArguments(AgentParticipant currentAgent, List<AgentThought> thoughts) {
        return thoughts.stream()
                .filter(t -> !t.getAgentId().equals(currentAgent.getId()))
                .filter(t -> t.getType() == AgentThought.ThoughtType.ARGUMENT)
                .map(t -> String.format("%s: %s", t.getAgentName(), t.getContent()))
                .collect(Collectors.joining("\n\n"));
    }

    private double calculateAgreementScore(Collection<AgentOpinion> opinions) {
        if (opinions.isEmpty())
            return 0.5;

        List<Double> confidenceScores = opinions.stream()
                .map(AgentOpinion::getConfidence)
                .sorted()
                .collect(Collectors.toList());

        int n = confidenceScores.size();
        if (n % 2 == 0) {
            return (confidenceScores.get(n / 2 - 1) + confidenceScores.get(n / 2)) / 2.0;
        } else {
            return confidenceScores.get(n / 2);
        }
    }

    private String synthesizeRecommendation(CollaborationSession session, Collection<AgentOpinion> opinions) {
        // Collect all expert opinions into a readable context
        String context = opinions.stream()
                .map(o -> String.format("Expert Recommendation: %s\nConfidence: %.2f\nKey Points: %s",
                        o.getRecommendation(), o.getConfidence(), String.join(", ", o.getKeyPoints())))
                .collect(Collectors.joining("\n\n---\n\n"));

        log.info("Synthesizing unified consensus from {} expert opinions", opinions.size());

        String prompt = promptRegistry.get("orch_synthesize_consensus").orElseThrow()
                .render(Map.of("context", context != null ? context : ""));

        try {
            LLMRequest request = LLMRequest.builder()
                    .addUserMessage(prompt)
                    .temperature(0.3) // Lower temperature for more consistent synthesis
                    .build();
            LLMResponse response = llmClient.chat(request);
            session.incrementStat("llm_calls");
            return response.getContent();
        } catch (Exception e) {
            log.error("Failed to synthesize consensus via LLM, falling back to basic join", e);
            return opinions.stream()
                    .map(AgentOpinion::getRecommendation)
                    .collect(Collectors.joining(" "));
        }
    }

    private List<String> extractKeyPoints(Collection<AgentOpinion> opinions) {
        return opinions.stream()
                .flatMap(o -> o.getKeyPoints().stream())
                .distinct()
                .limit(5)
                .collect(Collectors.toList());
    }

    private void indexThought(String sessionId, AgentThought thought) {
        try {
            String content = String.format("[%s] %s: %s", thought.getType(), thought.getAgentName(),
                    thought.getContent());
            io.github.llm4j.agent.rag.document.Document doc = io.github.llm4j.agent.rag.document.Document.builder()
                    .id(thought.getId())
                    .content(content)
                    .addMetadata("agent", thought.getAgentName())
                    .addMetadata("type", thought.getType().toString())
                    .build();

            // We use a helper in SharedKnowledgeService if we want, but for now RAGAgent
            // handles it.
            // Wait, RAGAgent has addDocument.
            // I'll add a helper to SharedKnowledgeService.
            sharedKnowledgeService.indexDocument(sessionId, doc);

            // Also extract knowledge triples
            extractKnowledge(sessionId, thought);

            // Persist as Turn
            sessionService.saveTurn(sessionId, thought.getAgentName(), thought.getContent());

        } catch (Exception e) {
            log.error("Failed to index thought for session {}", sessionId, e);
        }
    }

    private void extractKnowledge(String sessionId, AgentThought thought) {
        // Only extract from substantial thoughts
        if (thought.getType() == AgentThought.ThoughtType.REFINEMENT
                || thought.getType() == AgentThought.ThoughtType.REBUTTAL) {
            return;
        }

        try {
            String prompt = promptRegistry.get("orch_extract_knowledge").orElseThrow()
                    .render(Map.of("text", thought.getContent() != null ? thought.getContent() : ""));

            LLMRequest request = LLMRequest.builder()
                    .addUserMessage(prompt)
                    .temperature(0.0)
                    .build();

            LLMResponse response = llmClient.chat(request);
            // Increment stat but maybe separate it? For now assume it's an LLM call.
            CollaborationSession session = sessions.get(sessionId);
            if (session != null)
                session.incrementStat("llm_calls");

            String json = response.getContent().replaceAll("```json", "").replaceAll("```", "").trim();
            log.info("Knowledge Extraction Raw JSON: {}", json); // DEBUG LOG

            // Simple parsing (assuming library doesn't have a parser helper exposed)
            // We use Jackson if available? Or simple regex/string manipulation if Jackson
            // isn't handy?
            // Spring Boot has Jackson.
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, String>> triples = mapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {
                    });

            log.info("Parsed {} triples", triples.size()); // DEBUG LOG

            io.github.llm4j.agent.knowledge.KnowledgeGraph graph = sharedKnowledgeService.getKnowledgeGraph(sessionId);
            for (Map<String, String> t : triples) {
                String s = t.get("subject");
                String p = t.get("predicate");
                String o = t.get("object");
                if (s != null && p != null && o != null) {
                    io.github.llm4j.agent.knowledge.model.Triple triple = new io.github.llm4j.agent.knowledge.model.Triple(
                            io.github.llm4j.agent.knowledge.model.Entity.builder().id(s).type("concept").build(),
                            io.github.llm4j.agent.knowledge.model.Relation.builder().type(p).build(),
                            io.github.llm4j.agent.knowledge.model.Entity.builder().id(o).type("concept").build());

                    graph.addTriple(triple);

                    // BROADCAST LIVE UPDATE
                    try {
                        log.info("Broadcasting knowledge update: {}", s); // DEBUG LOG
                        messagingTemplate.convertAndSend("/topic/session/" + sessionId + "/knowledge", s); // Broadcast
                                                                                                           // Subject
                    } catch (Exception ex) {
                        log.error("Failed to broadcast knowledge update: {}", ex.getMessage(), ex); // ERROR LOG
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to extract knowledge from thought {}: {}", thought.getId(), e.getMessage(), e); // ERROR
                                                                                                              // LOG
        }
    }

    private void broadcastThought(String sessionId, AgentThought thought) {
        messagingTemplate.convertAndSend("/topic/session/" + sessionId + "/thoughts", thought);
    }

    private void broadcastSessionUpdate(String sessionId, CollaborationSession session) {
        // Persist state
        try {
            sessionService.updateSessionState(
                    sessionId,
                    session.getStatus().toString(),
                    session.getCurrentRound(),
                    session.getConsensus() != null ? session.getConsensus().getRecommendation() : null,
                    session.getConsensus() != null ? session.getConsensus().getAgreementScore() : null);
        } catch (Exception e) {
            log.warn("Failed to persist session state for {}: {}", sessionId, e.getMessage());
        }
        messagingTemplate.convertAndSend("/topic/session/" + sessionId + "/status", session);
    }

    private void broadcastConsensus(String sessionId, Consensus consensus) {
        // Persist consensus specifically
        try {
            CollaborationSession session = sessions.get(sessionId);
            if (session != null) {
                sessionService.updateSessionState(
                        sessionId,
                        session.getStatus().toString(),
                        session.getCurrentRound(),
                        consensus.getRecommendation(),
                        consensus.getAgreementScore());
            }
        } catch (Exception e) {
            log.warn("Failed to persist consensus for {}: {}", sessionId, e.getMessage());
        }
        messagingTemplate.convertAndSend("/topic/session/" + sessionId + "/consensus", consensus);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public CollaborationSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    private List<AgentParticipant> getShuffledAgents() {
        List<AgentParticipant> shuffled = new ArrayList<>(agents);
        Collections.shuffle(shuffled);
        return shuffled;
    }

    private void broadcastBurstThoughts(String sessionId, AgentParticipant agent, String fullContent,
            AgentThought.ThoughtType type, double confidence) {
        if (fullContent == null || fullContent.isEmpty()) {
            return;
        }
        log.info("Broadcasting burst thoughts for agent {}", agent.getName());
        CollaborationSession session = sessions.get(sessionId);

        // Split by paragraphs (double newlines) to preserve lists and tables
        String[] paragraphs = fullContent.split("\\n\\n+");
        List<String> chunks = new ArrayList<>();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty())
                continue;

            // Preserve lists, tables, and short paragraphs as single chunks
            // Regex matches: starts with list indicators (- * • 1.) or table indicator (|)
            boolean isMarkdownStructure = trimmed.matches("(?s)^(\\d+\\.|[-*•|])\\s+.*") || trimmed.contains("\n|");

            if (trimmed.length() > 200 && !isMarkdownStructure) {
                // Split long narrative paragraphs into sentences for the "burst" effect
                String[] sentences = trimmed.split("(?<!\\b[A-Z])(?<!\\b[A-Z][a-z])(?<!\\b\\d)(?<=[.!?])\\s+(?=[A-Z])");
                for (String s : sentences) {
                    if (!s.trim().isEmpty()) {
                        chunks.add(s.trim());
                    }
                }
            } else {
                chunks.add(trimmed);
            }
        }

        for (String chunk : chunks) {
            AgentThought thought = new AgentThought(
                    UUID.randomUUID().toString(),
                    agent.getId(),
                    agent.getName(),
                    chunk,
                    type,
                    Instant.now(),
                    new ArrayList<>(),
                    confidence);

            indexThought(sessionId, thought);
            session.addThought(thought);
            agent.addThought(thought);
            broadcastThought(sessionId, thought);

            // Delay for "burst" effect
            sleep(500);
        }

        // Longer pause after finishing turn
        sleep(1000);
    }
}
