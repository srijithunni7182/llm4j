package io.github.llm4j.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * File-based audit logger that writes events as JSON lines to a file.
 * Supports automatic file rotation when the file reaches a specified size.
 * 
 * Thread-safe implementation using synchronized methods.
 */
public class FileAuditLogger implements AuditLogger {

    private static final Logger logger = LoggerFactory.getLogger(FileAuditLogger.class);
    private static final long DEFAULT_MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int DEFAULT_MAX_FILES = 5;

    private final Path auditFilePath;
    private final ObjectMapper objectMapper;
    private final long maxFileSize;
    private final int maxFiles;

    /**
     * Creates a FileAuditLogger with default rotation settings (10MB, 5 files).
     *
     * @param auditFilePath the path to the audit log file
     */
    public FileAuditLogger(Path auditFilePath) {
        this(auditFilePath, DEFAULT_MAX_FILE_SIZE, DEFAULT_MAX_FILES);
    }

    /**
     * Creates a FileAuditLogger with custom rotation settings.
     *
     * @param auditFilePath the path to the audit log file
     * @param maxFileSize   maximum file size in bytes before rotation
     * @param maxFiles      maximum number of rotated files to keep
     */
    public FileAuditLogger(Path auditFilePath, long maxFileSize, int maxFiles) {
        this.auditFilePath = auditFilePath;
        this.maxFileSize = maxFileSize;
        this.maxFiles = maxFiles;
        this.objectMapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Ensure parent directory exists
        try {
            if (auditFilePath.getParent() != null) {
                Files.createDirectories(auditFilePath.getParent());
            }
        } catch (IOException e) {
            logger.error("Failed to create audit log directory", e);
        }
    }

    @Override
    public synchronized void logAgentDecision(AuditEvent event) {
        Map<String, Object> logEntry = new HashMap<>();
        logEntry.put("type", "AGENT_DECISION");
        logEntry.put("sessionId", event.getSessionId());
        logEntry.put("userId", event.getUserId());
        logEntry.put("timestamp", event.getTimestamp());
        logEntry.put("metadata", event.getMetadata());

        if (event.getAgentResult() != null) {
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("finalAnswer", event.getAgentResult().getFinalAnswer());
            resultData.put("stepCount", event.getAgentResult().getSteps().size());
            logEntry.put("result", resultData);
        }

        writeLogEntry(logEntry);
    }

    @Override
    public synchronized void logToolExecution(String sessionId, String toolName, String input, String output,
            Instant timestamp) {
        Map<String, Object> logEntry = new HashMap<>();
        logEntry.put("type", "TOOL_EXECUTION");
        logEntry.put("sessionId", sessionId);
        logEntry.put("toolName", toolName);
        logEntry.put("input", truncate(input, 1000));
        logEntry.put("output", truncate(output, 1000));
        logEntry.put("timestamp", timestamp);

        writeLogEntry(logEntry);
    }

    @Override
    public synchronized void logPromptUsage(String sessionId, String promptId, String version, Instant timestamp) {
        Map<String, Object> logEntry = new HashMap<>();
        logEntry.put("type", "PROMPT_USAGE");
        logEntry.put("sessionId", sessionId);
        logEntry.put("promptId", promptId);
        logEntry.put("version", version);
        logEntry.put("timestamp", timestamp);

        writeLogEntry(logEntry);
    }

    @Override
    public synchronized void logConversationEvent(String sessionId, String userId, String eventType,
            Map<String, Object> metadata) {
        Map<String, Object> logEntry = new HashMap<>();
        logEntry.put("type", "CONVERSATION_EVENT");
        logEntry.put("sessionId", sessionId);
        logEntry.put("userId", userId);
        logEntry.put("eventType", eventType);
        logEntry.put("metadata", metadata != null ? metadata : Map.of());
        logEntry.put("timestamp", Instant.now());

        writeLogEntry(logEntry);
    }

    private void writeLogEntry(Map<String, Object> logEntry) {
        try {
            // Check for rotation
            if (Files.exists(auditFilePath) && Files.size(auditFilePath) >= maxFileSize) {
                rotateFile();
            }

            // Write as JSON line
            String jsonLine = objectMapper.writeValueAsString(logEntry) + "\n";
            Files.writeString(auditFilePath, jsonLine,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);

        } catch (IOException e) {
            logger.error("Failed to write audit log entry", e);
        }
    }

    private void rotateFile() throws IOException {
        // Rotate existing files
        for (int i = maxFiles - 1; i > 0; i--) {
            Path source = getRotatedFilePath(i - 1);
            Path target = getRotatedFilePath(i);

            if (Files.exists(source)) {
                Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Rename current file to .1
        if (Files.exists(auditFilePath)) {
            Files.move(auditFilePath, getRotatedFilePath(0), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path getRotatedFilePath(int index) {
        String fileName = auditFilePath.getFileName().toString();
        return auditFilePath.getParent().resolve(fileName + "." + index);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
