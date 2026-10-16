package io.github.llm4j.nirmaan.tasks;

import io.github.llm4j.agent.task.TaskNotPerformed;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where a project lives on disk: {@code <workspace>/<id>/code} is what the agents build (and what is zipped at the end);
 * {@code <workspace>/<id>/meta} is the factory's own bookkeeping (the saved spec, command logs), which no agent can write to.
 *
 * <p>The workspace folder is the system property {@code nirmaan.workspace} (used by the tests), else {@code NIRMAAN_WORKSPACE}, else {@code ./workspace}. Every path an agent supplies goes through
 * {@link #resolve}, which refuses anything that would land outside the project's code folder.
 */
public final class Sandbox {

    private static final Pattern PROJECT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** Folders that are build output or tooling, never part of what an agent reads or what is delivered. */
    static final Set<String> IGNORED_DIRS = Set.of(".git", "target", "build", "node_modules", ".idea", ".venv", "venv", "__pycache__", "dist", ".gradle", ".pytest_cache");

    private Sandbox() {}

    public static Path workspace() {
        String configured = System.getProperty("nirmaan.workspace", System.getenv("NIRMAAN_WORKSPACE"));
        return Path.of(configured == null || configured.isBlank() ? "workspace" : configured).toAbsolutePath().normalize();
    }

    /** The folder the agents build in, created when it does not exist yet. */
    public static Path code(String projectId) {
        return code(workspace(), projectId);
    }

    static Path code(Path workspace, String projectId) {
        return ensure(project(workspace, projectId).resolve("code"));
    }

    /** The factory's own folder for a project: the saved spec and the logs of every command. */
    public static Path meta(String projectId) {
        return meta(workspace(), projectId);
    }

    static Path meta(Path workspace, String projectId) {
        return ensure(project(workspace, projectId).resolve("meta"));
    }

    static Path project(Path workspace, String projectId) {
        if (projectId == null || !PROJECT_ID.matcher(projectId).matches()) {
            throw new TaskNotPerformed("project id \"" + projectId + "\" must be 1 to 64 letters, digits, - or _");
        }
        return workspace.resolve(projectId);
    }

    /** A path the agent wrote, made safe: relative, inside {@code root}, and not inside a build-output folder. */
    public static Path resolve(Path root, String relative) {
        if (relative == null || relative.isBlank()) throw new TaskNotPerformed("a file has no path");
        if (relative.indexOf('\0') >= 0 || relative.contains("\\")) throw new TaskNotPerformed("path \"" + relative + "\" has characters that are not allowed");
        Path rel = Path.of(relative.strip());
        if (rel.isAbsolute()) throw new TaskNotPerformed("path \"" + relative + "\" is absolute; give a path inside the project");
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) throw new TaskNotPerformed("path \"" + relative + "\" leaves the project folder");
        Path inside = root.relativize(target);
        if (inside.getNameCount() == 0) throw new TaskNotPerformed("path \"" + relative + "\" is the project folder itself");
        for (Path part : inside) {
            String name = part.toString();
            if (IGNORED_DIRS.contains(name)) throw new TaskNotPerformed("path \"" + relative + "\" is inside " + name + ", which is build output");
        }
        return target;
    }

    private static Path ensure(Path dir) {
        try {
            return Files.createDirectories(dir).toRealPath();
        } catch (IOException e) {
            throw new TaskNotPerformed("cannot create " + dir + ": " + e.getMessage(), e);
        }
    }
}
