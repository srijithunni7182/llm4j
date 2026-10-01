package io.github.llm4j.tools;

import io.github.llm4j.agent.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * {@code tool Notes { use: file  root: "notes/"  mode: readwrite }}: lets an agent read, list, write and
 * append text files inside one directory of the script's directory. Nothing hidden, nothing the run itself
 * keeps (journal, trigger store), nothing outside.
 */
public final class FileKind extends GenericKind {

    @Override
    public String name() {
        return "file";
    }

    @Override
    public Set<String> optional() {
        return Set.of("root", "mode", "allow", "overwrite", "max_bytes", "on_unknown");
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        FileTool.Config c = FileTool.Config.parse(o);
        Path root = resolveRoot(c.root(), baseDir);
        if (Files.exists(root) && !Files.isDirectory(root)) throw new OptionException("root: " + c.root() + " is a file, not a directory");
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        FileTool.Config c = FileTool.Config.parse(o);
        return new FileTool(name, c, resolveRoot(c.root(), baseDir), context);
    }

    private static Path resolveRoot(String root, Path baseDir) {
        try {
            return SafePaths.inside(baseDir, root);
        } catch (IllegalArgumentException e) {
            throw new OptionException("root: " + e.getMessage());
        }
    }
}
