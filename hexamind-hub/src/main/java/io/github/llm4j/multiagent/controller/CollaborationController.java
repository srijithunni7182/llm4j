package io.github.llm4j.multiagent.controller;

import io.github.llm4j.multiagent.model.CollaborationSession;
import io.github.llm4j.multiagent.service.MultiAgentOrchestrator;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for collaboration sessions.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CollaborationController {

    private final MultiAgentOrchestrator orchestrator;
    private final io.github.llm4j.multiagent.service.SharedKnowledgeService sharedKnowledgeService;
    private final io.github.llm4j.hexamind.service.UserService userService;

    @PostMapping("/problems")
    public ResponseEntity<SessionResponse> submitProblem(@RequestBody ProblemRequest request,
            org.springframework.security.core.Authentication authentication) {
        String username = authentication.getName();
        io.github.llm4j.hexamind.model.User user = userService.findByUsername(username)
                .or(() -> userService.findByEmail(username))
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        String sessionId = orchestrator.startCollaboration(request.getProblem(), user);

        return ResponseEntity.ok(new SessionResponse(sessionId, "Collaboration started"));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<CollaborationSession> getSession(@PathVariable String sessionId) {
        CollaborationSession session = orchestrator.getSession(sessionId);

        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(session);
    }

    @PostMapping("/sessions/{sessionId}/feedback")
    public ResponseEntity<Void> submitFeedback(@PathVariable String sessionId, @RequestBody FeedbackRequest request) {
        orchestrator.processFeedback(sessionId, request.getFeedback());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/sessions/{sessionId}/stats")
    public ResponseEntity<SessionStats> getSessionStats(@PathVariable String sessionId) {
        CollaborationSession session = orchestrator.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        io.github.llm4j.multiagent.service.SharedKnowledgeService.KnowledgeStats knowledgeStats = sharedKnowledgeService
                .getKnowledgeStats(sessionId);

        // Safely get llm_calls, defaulting to 0
        int llmCalls = session.getStats().getOrDefault("llm_calls", 0);

        return ResponseEntity.ok(new SessionStats(
                knowledgeStats.getTripleCount(),
                knowledgeStats.getVectorCount(),
                llmCalls));
    }

    @Data
    public static class FeedbackRequest {
        private String feedback;
    }

    @Data
    public static class ProblemRequest {
        private String problem;
    }

    @Data
    @RequiredArgsConstructor
    public static class SessionResponse {
        private final String sessionId;
        private final String message;
    }

    @Data
    @RequiredArgsConstructor
    public static class SessionStats {
        private final int knowledgeNodes;
        private final int memoryVectors;
        private final int cognitiveSteps;
    }
}
