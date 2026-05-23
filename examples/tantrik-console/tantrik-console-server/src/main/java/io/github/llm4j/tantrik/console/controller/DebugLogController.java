package io.github.llm4j.tantrik.console.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Temporary debug endpoint — receives log lines from the React frontend
 * and appends them to ./debug-client.log so they survive page reloads.
 *
 * DELETE this controller once the reload bug is fixed.
 */
@RestController
@RequestMapping("/api/debug")
public class DebugLogController {

    private static final Logger log = LoggerFactory.getLogger(DebugLogController.class);
    private static final Path LOG_FILE = Paths.get("debug-client.log");

    @PostMapping(value = "/log", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> appendLog(@RequestBody String line) {
        String entry = Instant.now() + " " + line + "\n";
        try {
            Files.writeString(LOG_FILE, entry,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to write debug log: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/log")
    public ResponseEntity<Void> clearLog() {
        try {
            Files.deleteIfExists(LOG_FILE);
        } catch (IOException e) {
            log.error("Failed to clear debug log: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }
}
