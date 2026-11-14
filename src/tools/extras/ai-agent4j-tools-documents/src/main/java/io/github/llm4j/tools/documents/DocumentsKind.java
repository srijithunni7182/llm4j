package io.github.llm4j.tools.documents;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.tools.GenericKind;
import io.github.llm4j.tools.OptionException;
import io.github.llm4j.tools.Options;
import io.github.llm4j.tools.SafePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * {@code tool Docs { use: documents  root: "inbox" }}: the text of PDF, DOCX, HTML and plain text
 * files under a folder, with the page or paragraph each part came from (phase 35, D18 and its
 * amendment). It reads only; it reaches no network.
 */
public final class DocumentsKind extends GenericKind {

    static final long DEFAULT_BYTES = 20L * 1024 * 1024;
    static final long MAX_BYTES = 100L * 1024 * 1024;
    static final int DEFAULT_CHARS = 200_000;
    static final int MAX_CHARS = 2_000_000;
    static final int DEFAULT_PAGES = 500;
    static final int MAX_PAGES = 5_000;

    @Override
    public String name() {
        return "documents";
    }

    @Override
    public Set<String> required() {
        return Set.of("root");
    }

    @Override
    public Set<String> optional() {
        return Set.of("max_bytes", "max_chars", "max_pages");
    }

    /** A document's text was written by whoever wrote the document. */
    @Override
    public Set<String> uncheckedResult(Map<String, String> options) {
        return Set.of("text");
    }

    @Override
    public ToolProfile profile(Map<String, String> options) {
        return ToolProfile.of(
                true, true, false, false, "reads PDF, DOCX and HTML files under " + options.getOrDefault("root", "its root"));
    }

    /** The settings of one declaration, checked. */
    record Config(Path root, long maxBytes, int maxChars, int maxPages) {}

    static Config config(Options o, Path baseDir) {
        Path root;
        try {
            root = SafePaths.inside(baseDir, o.require("root"));
        } catch (IllegalArgumentException e) {
            throw new OptionException("root: " + e.getMessage());
        }
        return new Config(
                root,
                o.size("max_bytes", DEFAULT_BYTES, MAX_BYTES),
                o.integer("max_chars", DEFAULT_CHARS, 1, MAX_CHARS),
                o.integer("max_pages", DEFAULT_PAGES, 1, MAX_PAGES));
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        config(o, baseDir);
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        Config c = config(o, baseDir);
        if (!Files.isDirectory(c.root())) throw new OptionException("root: " + o.get("root") + " is not a folder");
        return new DocumentsTool(name, c, context);
    }
}
