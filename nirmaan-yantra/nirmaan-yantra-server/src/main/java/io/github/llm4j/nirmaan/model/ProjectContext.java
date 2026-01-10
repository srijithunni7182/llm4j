package io.github.llm4j.nirmaan.model;

import lombok.Data;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class ProjectContext {
    private String projectId;
    private String userIdea;
    private ProjectStatus status;
    private Path sandboxPath;

    // Artifacts: Name -> Content/Path
    private Map<String, String> artifacts = new ConcurrentHashMap<>();

    // Agent Logs for UI
    private StringBuilder activityLog = new StringBuilder();

    public ProjectContext(String userIdea) {
        this.projectId = UUID.randomUUID().toString();
        this.userIdea = userIdea;
        this.status = ProjectStatus.CREATED;
        this.initSandbox();
    }

    // Initialize a temporary sandbox directory
    private void initSandbox() {
        try {
            this.sandboxPath = Files.createTempDirectory("nirmaan-" + projectId);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create sandbox", e);
        }
    }

    public void addArtifact(String key, String content) {
        this.artifacts.put(key, content);
        System.out.println("DEBUG: addArtifact called for " + key);
        saveFile(key, content);
    }

    public void log(String agentName, String message) {
        // Only append Agent Name if it's not already in the message
        String logEntry;
        if (message.startsWith(agentName + ":") || message.startsWith("[" + agentName + "]")) {
            logEntry = message + "\n";
        } else {
            logEntry = String.format("%s: %s\n", agentName, message);
        }
        this.activityLog.append(logEntry);
    }

    public void saveFile(String relativePath, String content) {
        try {
            Path targetPath = sandboxPath.resolve(relativePath);
            Files.createDirectories(targetPath.getParent());
            Files.writeString(targetPath, content);
            System.out.println("DEBUG: File saved to " + targetPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("DEBUG: Failed to save file " + relativePath);
            e.printStackTrace();
            throw new RuntimeException("Failed to write to sandbox: " + relativePath, e);
        }
    }
}
