package io.github.llm4j.tantrik.console.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Simple health-check endpoint.
 * Requirement 8.3
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final String version;

    public HealthController(
            @Value("${info.app.version:unknown}") String version) {
        this.version = version;
    }

    /**
     * GET /api/health → {"status": "UP", "version": "<version>"}
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "version", version
        ));
    }
}
