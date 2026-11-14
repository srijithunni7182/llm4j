package io.github.llm4j.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolProfile;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * {@code tool Check { use: verify root: "out" }}: turns a verifier's result into one typed record
 * (phase 35, D12): a JUnit XML report, or a JSON document checked against a JSON Schema. Counts and
 * statuses are parsed by the host; the items' names and messages stay text a model may have
 * written. It runs no program. Without {@code root:} it takes text only and reaches nothing.
 */
public final class VerifyKind extends GenericKind {

    static final long DEFAULT_MAX_BYTES = 4L * 1024 * 1024;
    static final long MAX_BYTES_CEILING = 32L * 1024 * 1024;
    static final int DEFAULT_MAX_ITEMS = 200;
    static final int MAX_ITEMS_CEILING = 5000;

    @Override
    public String name() {
        return "verify";
    }

    @Override
    public Set<String> optional() {
        return Set.of("root", "max_bytes", "max_items");
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        o.size("max_bytes", DEFAULT_MAX_BYTES, MAX_BYTES_CEILING);
        o.integer("max_items", DEFAULT_MAX_ITEMS, 1, MAX_ITEMS_CEILING);
        if (o.has("root")) RootedFiles.resolveRoot(o.get("root"), baseDir);
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        Path root = o.has("root") ? RootedFiles.resolveRoot(o.get("root"), baseDir) : null;
        return new VerifyTool(
                name,
                new RootedFiles(root, o.size("max_bytes", DEFAULT_MAX_BYTES, MAX_BYTES_CEILING)),
                o.integer("max_items", DEFAULT_MAX_ITEMS, 1, MAX_ITEMS_CEILING),
                context);
    }

    /** Pure without a root; with one, it reads the files under it. */
    @Override
    public ToolProfile profile(Map<String, String> options) {
        String root = options.get("root");
        return root == null
                ? ToolProfile.pure("checks a report or a document it is given (junit, schema)")
                : ToolProfile.of(
                        false, true, false, false, "reads reports and documents in " + root);
    }

    /** Test names, messages and the reason a report could not be read come from the report. */
    @Override
    public Set<String> uncheckedResult(Map<String, String> options) {
        return Set.of("items", "error");
    }
}
