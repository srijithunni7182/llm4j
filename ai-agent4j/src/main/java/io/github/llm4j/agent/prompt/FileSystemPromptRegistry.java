package io.github.llm4j.agent.prompt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A file-system based implementation of PromptRegistry.
 * Supports hot-reloading via WatchService.
 * Expects a YAML file structure:
 * prompts:
 * prompt_id:
 * v1: "Template content..."
 * v2: "Updated content..."
 * latest: "v2"
 */
public class FileSystemPromptRegistry implements PromptRegistry, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FileSystemPromptRegistry.class);
    private final Path promptsFile;
    private final ObjectMapper mapper;

    // Map<PromptID, Map<Version, Content>>
    private final Map<String, Map<String, String>> promptStorage = new ConcurrentHashMap<>();

    private WatchService watchService;
    private ExecutorService watchExecutor;
    private volatile boolean isRunning = true;

    public FileSystemPromptRegistry(Path promptsFile) {
        this.promptsFile = promptsFile;
        this.mapper = new ObjectMapper(new YAMLFactory());

        // Initial load
        reload();

        // Start watcher
        startWatcher();
    }

    private void startWatcher() {
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            Path parent = promptsFile.getParent();
            if (parent != null) {
                parent.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

                this.watchExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "PromptRegistry-Watcher");
                    t.setDaemon(true);
                    return t;
                });

                watchExecutor.submit(this::watchLoop);
            }
        } catch (IOException e) {
            log.error("Failed to start file watcher for prompts: {}", e.getMessage());
        }
    }

    private void watchLoop() {
        while (isRunning) {
            try {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = (Path) event.context();
                    if (changed.toString().equals(promptsFile.getFileName().toString())) {
                        log.info("Creating reload trigger for modified prompt file: {}", changed);
                        // Small delay to ensure write completion
                        Thread.sleep(100);
                        reload();
                    }
                }
                boolean valid = key.reset();
                if (!valid) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Error in prompt watcher loop: {}", e.getMessage());
            }
        }
    }

    @Override
    public Optional<PromptTemplate> get(String id) {
        Map<String, String> versions = promptStorage.get(id);
        if (versions == null)
            return Optional.empty();

        String latestVersion = versions.get("latest");
        if (latestVersion == null) {
            // Fallback: try to find the "highest" key that isn't 'latest'?
            // Or just return empty if 'latest' pointer missing.
            // Let's assume strict structure for now or return any if size 1.
            return Optional.empty();
        }

        return get(id, latestVersion);
    }

    @Override
    public Optional<PromptTemplate> get(String id, String version) {
        Map<String, String> versions = promptStorage.get(id);
        if (versions == null)
            return Optional.empty();

        String content = versions.get(version);
        if (content == null)
            return Optional.empty();

        return Optional.of(new PromptTemplate(id, version, content));
    }

    @Override
    public synchronized void reload() {
        if (!Files.exists(promptsFile)) {
            log.warn("Prompts file not found: {}", promptsFile);
            return;
        }

        try {
            // Structure: { "prompts": { "id": { "v1": "...", "latest": "..." } } }
            Map<String, Map<String, Map<String, String>>> root = mapper.readValue(
                    promptsFile.toFile(),
                    new TypeReference<Map<String, Map<String, Map<String, String>>>>() {
                    });

            if (root.containsKey("prompts")) {
                Map<String, Map<String, String>> newPrompts = root.get("prompts");
                promptStorage.clear();
                promptStorage.putAll(newPrompts);
                log.info("Loaded {} prompts from {}", newPrompts.size(), promptsFile);
            }

        } catch (IOException e) {
            log.error("Failed to load prompts file: {}", e.getMessage());
        }
    }

    @Override
    public void close() throws Exception {
        isRunning = false;
        if (watchExecutor != null) {
            watchExecutor.shutdownNow();
        }
        if (watchService != null) {
            watchService.close();
        }
    }
}
