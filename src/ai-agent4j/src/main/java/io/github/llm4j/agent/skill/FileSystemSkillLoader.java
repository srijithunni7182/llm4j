package io.github.llm4j.agent.skill;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A {@link SkillLoader} that loads skills from the filesystem.
 *
 * <p>When constructed with a base directory, relative source paths are resolved against it.
 * Absolute paths are used as-is.
 */
public class FileSystemSkillLoader implements SkillLoader {

    private final Path baseDir;

    /** Creates a loader without a base directory. Source paths must be absolute. */
    public FileSystemSkillLoader() {
        this.baseDir = null;
    }

    /**
     * Creates a loader that resolves relative filenames against {@code baseDir}.
     *
     * @param baseDir the base directory for relative paths
     */
    public FileSystemSkillLoader(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir cannot be null");
    }

    /**
     * Loads a skill from the given filename or path. If a base directory was provided, relative
     * paths are resolved against it.
     *
     * @param filename the filename or path to load
     * @return the loaded {@link AgentSkill}
     * @throws IOException if the file cannot be read
     */
    @Override
    public AgentSkill load(String filename) throws IOException {
        Objects.requireNonNull(filename, "filename cannot be null");
        Path path = Path.of(filename);
        if (baseDir != null && !path.isAbsolute()) {
            path = baseDir.resolve(filename);
        }
        return AgentSkill.fromFile(path);
    }
}
