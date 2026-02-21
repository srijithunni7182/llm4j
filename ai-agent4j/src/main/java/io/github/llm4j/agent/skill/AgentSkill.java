package io.github.llm4j.agent.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Represents a skill — domain knowledge or instructions injected as markdown content into the
 * agent's system prompt.
 *
 * <p>Skills enrich the system prompt with structured context, similar to how {@link
 * io.github.llm4j.agent.persona.AgentPersona} enriches it with behavioral identity.
 */
public class AgentSkill {

    private final String name;
    private final String content;

    private AgentSkill(String name, String content) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("skill name must not be null or blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("skill content must not be null or blank");
        }
        this.name = name;
        this.content = content;
    }

    /**
     * Creates an AgentSkill directly from name and content strings.
     *
     * @param name the skill name (must not be null or blank)
     * @param content the markdown content (must not be null or blank)
     * @return a new AgentSkill instance
     */
    public static AgentSkill of(String name, String content) {
        return new AgentSkill(name, content);
    }

    /**
     * Creates an AgentSkill by reading a file from the filesystem. The skill name is inferred from
     * the filename (e.g. {@code coding-tips.md} → {@code "Coding Tips"}).
     *
     * @param filePath path to the markdown file
     * @return a new AgentSkill instance
     * @throws IOException if the file cannot be read
     */
    public static AgentSkill fromFile(Path filePath) throws IOException {
        Objects.requireNonNull(filePath, "filePath cannot be null");
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        String name = inferNameFromPath(filePath.getFileName().toString());
        return new AgentSkill(name, content);
    }

    /**
     * Creates an AgentSkill by reading a classpath resource. The skill name is inferred from the
     * resource path filename (e.g. {@code skills/coding-tips.md} → {@code "Coding Tips"}).
     *
     * @param resourcePath classpath resource path (e.g. {@code "skills/my-skill.md"})
     * @return a new AgentSkill instance
     * @throws IOException if the resource cannot be found or read
     */
    public static AgentSkill fromClasspath(String resourcePath) throws IOException {
        Objects.requireNonNull(resourcePath, "resourcePath cannot be null");
        InputStream stream = AgentSkill.class.getClassLoader().getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IOException("Classpath resource not found: " + resourcePath);
        }
        String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        String filename =
                resourcePath.contains("/")
                        ? resourcePath.substring(resourcePath.lastIndexOf('/') + 1)
                        : resourcePath;
        String name = inferNameFromPath(filename);
        return new AgentSkill(name, content);
    }

    /**
     * Converts this skill into a system prompt section.
     *
     * @return formatted section string: {@code "### {name}\n{content}"}
     */
    public String toSystemPromptSection() {
        return "### " + name + "\n" + content;
    }

    public String getName() {
        return name;
    }

    public String getContent() {
        return content;
    }

    /**
     * Infers a human-readable name from a filename. Strips the file extension, replaces hyphens and
     * underscores with spaces, and title-cases each word.
     *
     * <p>Examples:
     *
     * <ul>
     *   <li>{@code "coding-tips.md"} → {@code "Coding Tips"}
     *   <li>{@code "security_guidelines.md"} → {@code "Security Guidelines"}
     * </ul>
     */
    static String inferNameFromPath(String filename) {
        // Strip extension
        int dotIndex = filename.lastIndexOf('.');
        String base = (dotIndex > 0) ? filename.substring(0, dotIndex) : filename;
        // Replace hyphens and underscores with spaces
        String spaced = base.replace('-', ' ').replace('_', ' ');
        // Title-case each word
        String[] words = spaced.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) result.append(word.substring(1).toLowerCase());
        }
        return result.toString();
    }
}
