package io.github.llm4j.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.agent.tool.ToolProfile;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code tool Data { use: workspace root: "work" allow: "**" mode: readwrite protect: "tests/**"
 * }}: a directory an agent reads and edits (phase 35, D11). Beside {@code file}, which is
 * unchanged: exact-once patches, find and grep, and snapshots a script restores to try again (the
 * D9 pattern). {@code allow} is required; links are never followed; {@code .git} is never written
 * and keys and environment files are never read, whatever {@code allow} says.
 */
public final class WorkspaceKind extends GenericKind {

    static final List<String> DEFAULT_IGNORE =
            List.of(
                    "node_modules/**",
                    "target/**",
                    "build/**",
                    "dist/**",
                    ".git/**",
                    "__pycache__/**",
                    ".venv/**");
    static final long DEFAULT_MAX_BYTES = 256 * 1024;
    static final long MAX_BYTES_CEILING = 8L * 1024 * 1024;
    static final int DEFAULT_MAX_FILES = 5000;
    static final int MAX_FILES_CEILING = 100_000;
    static final long DEFAULT_SNAPSHOT_BYTES = 64L * 1024 * 1024;
    static final long SNAPSHOT_BYTES_CEILING = 2L * 1024 * 1024 * 1024;
    static final int DEFAULT_SNAPSHOTS = 8;
    static final int SNAPSHOTS_CEILING = 32;
    static final int DEFAULT_MAX_MATCHES = 200;
    static final int MAX_MATCHES_CEILING = 5000;

    @Override
    public String name() {
        return "workspace";
    }

    @Override
    public Set<String> required() {
        return Set.of("allow");
    }

    @Override
    public Set<String> optional() {
        return Set.of(
                "root",
                "mode",
                "overwrite",
                "protect",
                "ignore",
                "store",
                "max_bytes",
                "max_files",
                "max_snapshot_bytes",
                "snapshots",
                "max_matches",
                "on_unknown");
    }

    static WorkspaceTool.Config config(Options o) {
        List<String> allow = o.list("allow");
        if (allow.isEmpty())
            throw new OptionException(
                    "allow: is required: the paths the agent may read and write, e.g. \"**\" or \"docs/**, *.md\"");
        return new WorkspaceTool.Config(
                WorkspaceTool.Mode.valueOf(
                        o.choice("mode", "read", "read", "write", "readwrite")
                                .toUpperCase(Locale.ROOT)),
                new Globs(allow),
                new Globs(o.list("protect")),
                new Globs(o.has("ignore") ? o.list("ignore") : DEFAULT_IGNORE),
                o.bool("overwrite", false),
                o.size("max_bytes", DEFAULT_MAX_BYTES, MAX_BYTES_CEILING),
                o.integer("max_files", DEFAULT_MAX_FILES, 1, MAX_FILES_CEILING),
                o.size("max_snapshot_bytes", DEFAULT_SNAPSHOT_BYTES, SNAPSHOT_BYTES_CEILING),
                o.integer("max_matches", DEFAULT_MAX_MATCHES, 1, MAX_MATCHES_CEILING),
                EffectPolicy.parse(o.choice("on_unknown", "skip", "skip", "retry")));
    }

    static Path root(Options o, Path baseDir) {
        return RootedFiles.resolveRoot(o.string("root", "."), baseDir);
    }

    /**
     * Where snapshots and a sandbox's leases are kept: {@code store:}, or a folder of the tool's
     * own beside the script. Either way it is outside the root: a sandbox mounts the root
     * read-write, and a program there that could write the store could forge a lease (a port to
     * reach) or a snapshot (files a restore writes on the host).
     */
    static Path store(Options o, Path baseDir, Path root, String toolName) {
        Path store;
        if (!o.has("store")) {
            store = baseDir.toAbsolutePath().normalize().resolve(".loom-workspace").resolve(toolName);
            if (store.startsWith(root))
                throw new OptionException(
                        "store: is needed: the default store (.loom-workspace beside the script) is inside the workspace root, where a sandbox's programs could rewrite snapshots and leases; give root: a folder of its own, e.g. root: \"work\"");
            return store;
        }
        try {
            store = SafePaths.inside(baseDir, o.get("store"));
        } catch (IllegalArgumentException e) {
            throw new OptionException("store: " + e.getMessage());
        }
        if (store.startsWith(root))
            throw new OptionException(
                    "store: must be outside the workspace root, so the agent's own tools cannot reach it");
        return store;
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        config(o);
        Path root = root(o, baseDir);
        store(o, baseDir, root, "check");
        o.integer("snapshots", DEFAULT_SNAPSHOTS, 1, SNAPSHOTS_CEILING);
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        Path root = root(o, baseDir);
        return new WorkspaceTool(
                name,
                config(o),
                root,
                store(o, baseDir, root, name),
                o.integer("snapshots", DEFAULT_SNAPSHOTS, 1, SNAPSHOTS_CEILING),
                context);
    }

    /** Reaches the workspace's files; changes them unless read only; no network. */
    @Override
    public ToolProfile profile(Map<String, String> options) {
        String mode = options.getOrDefault("mode", "read");
        String protect = options.get("protect");
        return ToolProfile.of(
                        false,
                        true,
                        false,
                        !mode.equals("read"),
                        mode
                                + " in the workspace "
                                + options.getOrDefault("root", ".")
                                + (protect == null ? "" : "; protected: " + protect))
                .journaled(true);
    }

    /** The files' text and names may have been written by a model (an agent edits them). */
    @Override
    public Set<String> uncheckedResult(Map<String, String> options) {
        return Set.of("content", "entries", "paths", "matches", "added", "changed", "deleted");
    }
}
