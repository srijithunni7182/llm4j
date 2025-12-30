package io.github.llm4j.multiagent.service;

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

    public MultiAgentOrchestrator(List<AgentParticipant> agents,
            SimpMessagingTemplate messagingTemplate,
            io.github.llm4j.LLMClient llmClient,
            SharedKnowledgeService sharedKnowledgeService) {
        this.agents = agents;
        this.messagingTemplate = messagingTemplate;
        this.llmClient = llmClient;
        this.sharedKnowledgeService = sharedKnowledgeService;
    }

    /**
     * Start a new collaboration session.
     */
    public String startCollaboration(String problem) {
        String sessionId = UUID.randomUUID().toString();

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

            String refinement = agent.respond(sessionId,
                    "The user has provided the following feedback on the group's analysis. " +
                            "Re-evaluate your previous stance and provide an updated perspective addressing this feedback:\n\n"
                            +
                            "USER FEEDBACK: " + feedback);

            AgentThought thought = new AgentThought(
                    UUID.randomUUID().toString(),
                    agent.getId(),
                    agent.getName(),
                    refinement,
                    AgentThought.ThoughtType.REFINEMENT,
                    Instant.now(),
                    new ArrayList<>(),
                    0.9);

            indexThought(sessionId, thought);
            session.addThought(thought);
            agent.addThought(thought);
            broadcastThought(sessionId, thought);

            sleep(1000);
        }
    }

    private void conductAnalysisRound(String sessionId) {
        CollaborationSession session = sessions.get(sessionId);

        // Define a strict fact-checking instruction for Round 1
        String factCheckInstruction = "\n\nCRITICAL ROUND 1 RULE: Your primary mission in this round is literal verification. "
                +
                "Use your Web Search tool to confirm if the problem statement contains fabricated, non-existent, or fictional terms. "
                +
                "If you find no empirical evidence for a term, you MUST debunk it immediately. " +
                "STRICTLY AVOID: 'phased approach', 'holistic view', 'proceed with caution'. Be precise and technical.";

        for (AgentParticipant agent : getShuffledAgents()) {
            log.info("Agent {} analyzing problem with fact-check instruction", agent.getName());
            session.incrementStat("llm_calls");

            String analysis = agent.analyze(sessionId, session.getProblem() + factCheckInstruction);

            AgentThought thought = new AgentThought(
                    UUID.randomUUID().toString(),
                    agent.getId(),
                    agent.getName(),
                    analysis,
                    AgentThought.ThoughtType.ANALYSIS,
                    Instant.now(),
                    new ArrayList<>(),
                    0.7);

            indexThought(sessionId, thought);
            session.addThought(thought);
            agent.addThought(thought);
            broadcastThought(sessionId, thought);

            // Small delay for better UX
            sleep(1000);
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

            String argument = agent.argue(sessionId, session.getProblem(), context);

            AgentThought thought = new AgentThought(
                    UUID.randomUUID().toString(),
                    agent.getId(),
                    agent.getName(),
                    argument,
                    AgentThought.ThoughtType.ARGUMENT,
                    Instant.now(),
                    new ArrayList<>(),
                    0.8);

            indexThought(sessionId, thought);
            session.addThought(thought);
            agent.addThought(thought);
            broadcastThought(sessionId, thought);

            sleep(1000);
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

            String critique = agent.respond(sessionId,
                    "Critique the following arguments objectively. Look for logical fallacies, missing data, or potential downsides:\n\n"
                            + otherArguments);

            AgentThought thought = new AgentThought(
                    UUID.randomUUID().toString(),
                    agent.getId(),
                    agent.getName(),
                    critique,
                    AgentThought.ThoughtType.CRITIQUE,
                    Instant.now(),
                    new ArrayList<>(),
                    0.8);

            indexThought(sessionId, thought);
            session.addThought(thought);
            agent.addThought(thought);
            broadcastThought(sessionId, thought);

            sleep(1000);
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

            String rebuttal = agent
                    .respond(sessionId,
                            "Defend your position against these critiques and clarify any misunderstandings:\n\n"
                                    + relevantThoughts);

            AgentThought thought = new AgentThought(
                    UUID.randomUUID().toString(),
                    agent.getId(),
                    agent.getName(),
                    rebuttal,
                    AgentThought.ThoughtType.REBUTTAL,
                    Instant.now(),
                    new ArrayList<>(),
                    0.85);

            indexThought(sessionId, thought);
            session.addThought(thought);
            agent.addThought(thought);
            broadcastThought(sessionId, thought);

            sleep(1000);
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

            String response = agent.respond(sessionId, otherArguments);

            AgentThought thought = new AgentThought(
                    UUID.randomUUID().toString(),
                    agent.getId(),
                    agent.getName(),
                    response,
                    AgentThought.ThoughtType.COUNTER_ARGUMENT,
                    Instant.now(),
                    new ArrayList<>(),
                    0.75);

            indexThought(sessionId, thought);
            session.addThought(thought);
            agent.addThought(thought);
            broadcastThought(sessionId, thought);

            sleep(1000);
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

        String prompt = "You are a Master Coordinator synthesizing analysis from a group of diverse experts. " +
                "Your goal is to provide a single, unified, and professional recommendation that represents the group's collective wisdom. "
                +
                "CRITICAL: If any experts have flagged terms in the query as fabricated, fictional, or non-existent (hallucinations), you MUST state this clearly at the beginning. "
                +
                "Do not entertain speculative strategies for non-existent technologies. "
                +
                "\n\n### EXPERT OPINIONS:\n" + context +
                "\n\n### INSTRUCTIONS:\n" +
                "1. Merge all perspectives into a single, coherent narrative. Do not list individual experts; present it as a collective conclusion.\n"
                +
                "2. Address critical risks and skeptical viewpoints raised. If an expert finds no empirical backing for a term, that is the most important finding.\n"
                +
                "3. STRICTLY AVOID corporate cliches such as 'phased approach', 'proceed with caution', 'holistic strategy', or 'premature discussion'. Use precise, data-driven language.\n"
                +
                "4. If the premise is faulty (e.g., fabricated terms), the 'Consolidated Strategy' should be to debunk or correct the premise rather than providing a plan for it.\n"
                +
                "5. Provide a clear, actionable 'Consolidated Strategy'.\n\n" +
                "FINAL UNIFIED RECOMMENDATION:";

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
            String prompt = "Extract knowledge triples (Subject, Predicate, Object) from the following text. " +
                    "Return ONLY a JSON array of objects with keys 'subject', 'predicate', 'object'. " +
                    "Example: [{\"subject\": \"Solar Energy\", \"predicate\": \"is_source_of\", \"object\": \"Power\"}] \n\n"
                    +
                    "Text: " + thought.getContent();

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
            // Simple parsing (assuming library doesn't have a parser helper exposed)
            // We use Jackson if available? Or simple regex/string manipulation if Jackson
            // isn't handy?
            // Spring Boot has Jackson.
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, String>> triples = mapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {
                    });

            io.github.llm4j.agent.knowledge.KnowledgeGraph graph = sharedKnowledgeService.getKnowledgeGraph(sessionId);
            for (Map<String, String> t : triples) {
                String s = t.get("subject");
                String p = t.get("predicate");
                String o = t.get("object");
                if (s != null && p != null && o != null) {
                    graph.addTriple(new io.github.llm4j.agent.knowledge.model.Triple(
                            io.github.llm4j.agent.knowledge.model.Entity.builder().id(s).type("concept").build(),
                            io.github.llm4j.agent.knowledge.model.Relation.builder().type(p).build(),
                            io.github.llm4j.agent.knowledge.model.Entity.builder().id(o).type("concept").build()));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract knowledge from thought {}: {}", thought.getId(), e.getMessage());
        }
    }

    private void broadcastThought(String sessionId, AgentThought thought) {
        messagingTemplate.convertAndSend("/topic/session/" + sessionId + "/thoughts", thought);
    }

    private void broadcastSessionUpdate(String sessionId, CollaborationSession session) {
        messagingTemplate.convertAndSend("/topic/session/" + sessionId + "/status", session);
    }

    private void broadcastConsensus(String sessionId, Consensus consensus) {
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
}
