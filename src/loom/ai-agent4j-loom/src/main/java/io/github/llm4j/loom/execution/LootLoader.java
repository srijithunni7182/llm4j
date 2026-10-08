package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.logging.Logger;

/**
 * Reads a {@code .loot} file: {@code Name = com.acme.Class} per tool. A line {@code Name.reach = reads} (none, reads, fetches, writes or sends) says
 * what that tool reaches, for {@code weave audit}, which never loads the class and so believes only what is written here.
 */
public class LootLoader {
    static final String REACH_SUFFIX = ".reach";

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
            if (key.toString().trim().endsWith(REACH_SUFFIX)) return; // a declaration about a tool, not a tool
            String toolName = key.toString().trim();
            String fqcn = value.toString().trim();
            try {
                Class<?> clazz = Class.forName(fqcn, true, io.github.llm4j.loom.init.ProjectClasses.loader());
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

    /** The declared reach per tool name, as written ({@code Name.reach = reads}); empty when there is no file or no declaration. */
    public static Map<String, String> reaches(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        Properties p = read(file);
        if (p == null) return out;
        p.forEach((k, v) -> {
            String key = k.toString().trim();
            if (key.endsWith(REACH_SUFFIX) && key.length() > REACH_SUFFIX.length()) out.put(key.substring(0, key.length() - REACH_SUFFIX.length()), v.toString().trim());
        });
        return out;
    }

    /** What is wrong with the file's reach lines: a word that is not one of the five, or a declaration for a tool the file does not map. */
    public static List<String> reachProblems(Path file) {
        List<String> out = new ArrayList<>();
        Properties p = read(file);
        if (p == null) return out;
        for (Map.Entry<String, String> e : reaches(file).entrySet()) {
            if (!io.github.llm4j.loom.tools.ToolFactory.REACHES.contains(e.getValue())) {
                out.add(file.getFileName() + ": " + e.getKey() + REACH_SUFFIX + " must be one of " + String.join(", ", new TreeSet<>(io.github.llm4j.loom.tools.ToolFactory.REACHES)) + ", not " + e.getValue());
            }
            if (!p.containsKey(e.getKey())) out.add(file.getFileName() + ": " + e.getKey() + REACH_SUFFIX + " says what a tool reaches, but " + e.getKey() + " is not mapped to a class in this file");
        }
        return out;
    }

    private static Properties read(Path file) {
        if (file == null || !Files.exists(file)) return null;
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            p.load(reader);
            return p;
        } catch (IOException e) {
            return null;
        }
    }
}
