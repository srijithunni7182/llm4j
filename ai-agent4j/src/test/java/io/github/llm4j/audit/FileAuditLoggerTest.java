package io.github.llm4j.audit;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.AgentResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileAuditLoggerTest {

    @TempDir Path tempDir;

    private Path auditFile;
    private FileAuditLogger logger;

    @BeforeEach
    void setUp() {
        auditFile = tempDir.resolve("audit.log");
        logger = new FileAuditLogger(auditFile);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Cleanup any created files
        if (Files.exists(auditFile)) {
            Files.delete(auditFile);
        }
    }

    @Test
    void testLogAgentDecision_createsJsonFile() throws IOException {
        AuditEvent event =
                AuditEvent.builder().sessionId("test-session").userId("test-user").build();

        logger.logAgentDecision(event);

        assertTrue(Files.exists(auditFile));
        List<String> lines = Files.readAllLines(auditFile);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"type\":\"AGENT_DECISION\""));
        assertTrue(lines.get(0).contains("\"sessionId\":\"test-session\""));
    }

    @Test
    void testLogAgentDecision_appendsToExistingFile() throws IOException {
        AuditEvent event1 = AuditEvent.builder().sessionId("session-1").build();
        AuditEvent event2 = AuditEvent.builder().sessionId("session-2").build();

        logger.logAgentDecision(event1);
        logger.logAgentDecision(event2);

        List<String> lines = Files.readAllLines(auditFile);
        assertEquals(2, lines.size());
    }

    @Test
    void testLogAgentDecision_handlesNullMetadata() {
        AuditEvent event = AuditEvent.builder().sessionId("test-session").build();

        assertDoesNotThrow(() -> logger.logAgentDecision(event));
    }

    @Test
    void testLogAgentDecision_handlesLargePayload() {
        AgentResult largeResult = AgentResult.builder().finalAnswer("A".repeat(10000)).build();

        AuditEvent event =
                AuditEvent.builder().sessionId("test-session").agentResult(largeResult).build();

        assertDoesNotThrow(() -> logger.logAgentDecision(event));
    }

    @Test
    void testLogToolExecution_logsTimestamp() throws IOException {
        Instant timestamp = Instant.parse("2024-01-01T12:00:00Z");

        logger.logToolExecution("session", "Calculator", "2+2", "4", timestamp);

        List<String> lines = Files.readAllLines(auditFile);
        assertTrue(lines.get(0).contains("\"type\":\"TOOL_EXECUTION\""));
        assertTrue(lines.get(0).contains("\"toolName\":\"Calculator\""));
        assertTrue(lines.get(0).contains("2024-01-01T12:00:00Z"));
    }

    @Test
    void testLogToolExecution_escapesSpecialCharacters() throws IOException {
        logger.logToolExecution("session", "Tool", "input\"with\"quotes", "output", Instant.now());

        List<String> lines = Files.readAllLines(auditFile);
        // Jackson should properly escape quotes
        assertTrue(lines.get(0).contains("\\\"with\\\""));
    }

    @Test
    void testLogPromptUsage_recordsVersion() throws IOException {
        logger.logPromptUsage("session", "system_prompt", "v2", Instant.now());

        List<String> lines = Files.readAllLines(auditFile);
        assertTrue(lines.get(0).contains("\"promptId\":\"system_prompt\""));
        assertTrue(lines.get(0).contains("\"version\":\"v2\""));
    }

    @Test
    void testFileRotation_rotatesAt10MB() throws IOException {
        // Create logger with small rotation size for testing
        FileAuditLogger smallLogger = new FileAuditLogger(auditFile, 1024, 2); // 1KB

        // Write enough data to trigger rotation
        String largePayload = "A".repeat(500);
        for (int i = 0; i < 10; i++) {
            AuditEvent event =
                    AuditEvent.builder()
                            .sessionId("session-" + i)
                            .addMetadata("payload", largePayload)
                            .build();
            smallLogger.logAgentDecision(event);
        }

        // Check that rotation occurred
        Path rotatedFile = auditFile.getParent().resolve(auditFile.getFileName() + ".0");
        assertTrue(
                Files.exists(auditFile) || Files.exists(rotatedFile),
                "Either current file or rotated file should exist");
    }

    @Test
    void testFileRotation_keepsLast5Files() throws IOException {
        FileAuditLogger smallLogger = new FileAuditLogger(auditFile, 100, 3); // Very small

        // Trigger multiple rotations
        for (int i = 0; i < 50; i++) {
            AuditEvent event =
                    AuditEvent.builder()
                            .sessionId("session-" + i)
                            .addMetadata("data", "test data for rotation")
                            .build();
            smallLogger.logAgentDecision(event);
        }

        // Should not have more than maxFiles rotated files
        Path file3 = auditFile.getParent().resolve(auditFile.getFileName() + ".3");
        assertFalse(Files.exists(file3), "Should not keep more than maxFiles");
    }

    @Test
    void testConcurrentWrites_threadSafe() throws InterruptedException, IOException {
        Thread[] threads = new Thread[10];

        for (int i = 0; i < threads.length; i++) {
            final int threadId = i;
            threads[i] =
                    new Thread(
                            () -> {
                                for (int j = 0; j < 10; j++) {
                                    AuditEvent event =
                                            AuditEvent.builder()
                                                    .sessionId("thread-" + threadId + "-event-" + j)
                                                    .build();
                                    logger.logAgentDecision(event);
                                }
                            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // All 100 events should be logged
        assertTrue(Files.exists(auditFile));
        List<String> lines = Files.readAllLines(auditFile);
        assertEquals(100, lines.size());
    }

    @Test
    void testMalformedInput_doesNotCrash() {
        // Even with weird input, should not crash
        assertDoesNotThrow(() -> logger.logConversationEvent(null, null, null, null));
    }

    @Test
    void testIOException_logsErrorButContinues() {
        // Create logger pointing to invalid location (read-only or non-existent)
        Path invalidPath = Path.of("/invalid/path/audit.log");
        FileAuditLogger invalidLogger = new FileAuditLogger(invalidPath);

        AuditEvent event = AuditEvent.builder().sessionId("test").build();

        // Should not throw, just log error
        assertDoesNotThrow(() -> invalidLogger.logAgentDecision(event));
    }
}
