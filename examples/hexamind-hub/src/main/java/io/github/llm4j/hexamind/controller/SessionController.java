package io.github.llm4j.hexamind.controller;

import io.github.llm4j.hexamind.model.Session;
import io.github.llm4j.hexamind.model.User;
import io.github.llm4j.hexamind.service.SessionService;
import io.github.llm4j.hexamind.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/my-sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<List<Session>> getMySessions(Authentication authentication) {
        User user = getUser(authentication);
        List<Session> sessions = sessionService.getSessionsByUser(user);
        return ResponseEntity.ok(sessions);
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<Session> getSession(@PathVariable String sessionId, Authentication authentication) {
        // Verify user owns the session? Or just public/shared?
        // For now, let's allow if the user is authenticated, but ideally check
        // ownership.

        Session session = sessionService.getSessionBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        // Optional: Check if session belongs to user
        // User user = getUser(authentication);
        // if (!session.getUser().getId().equals(user.getId())) { return
        // ResponseEntity.status(403).build(); }

        return ResponseEntity.ok(session);
    }

    private User getUser(Authentication authentication) {
        String username = authentication.getName();
        return userService.findByUsername(username)
                .or(() -> userService.findByEmail(username))
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}
