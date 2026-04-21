package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.logging.Logger;

public class LootLoader {
    private static final Logger log = Logger.getLogger(LootLoader.class.getName());

    public void loadIntoRegistry(String lootFilePath, ToolRegistry registry) {
        Path path = Paths.get(lootFilePath);
        if (!Files.exists(path)) {
            log.warning("Loot file not found: " + lootFilePath);
            return;
        }

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException e) {
            log.severe("Failed to read loot file: " + e.getMessage());
            return;
        }

        properties.forEach((key, value) -> {
            String toolName = key.toString().trim();
            String fqcn = value.toString().trim();
            try {
                Class<?> clazz = Class.forName(fqcn);
                if (Tool.class.isAssignableFrom(clazz)) {
                    Tool toolInstance = (Tool) clazz.getDeclaredConstructor().newInstance();
                    registry.register(toolName, toolInstance);
                    log.info("Successfully loaded tool: " + toolName + " -> " + fqcn);
                } else {
                    log.warning("Class " + fqcn + " does not implement io.github.llm4j.agent.Tool");
                }
            } catch (Exception e) {
                log.severe("Failed to instantiate tool " + toolName + " from class " + fqcn + ": " + e.getMessage());
            }
        });
    }
}
