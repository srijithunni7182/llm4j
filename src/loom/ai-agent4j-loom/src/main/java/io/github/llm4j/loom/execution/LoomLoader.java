package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Orchestrates the loading of Loom scripts, resolving imports recursively and detecting circular
 * dependencies.
 */
public class LoomLoader {
    private static final Logger log = Logger.getLogger(LoomLoader.class.getName());

    private final Set<String> visitedFiles = new HashSet<>();

    public LoomScript load(String filePath) throws IOException {
        return load(
                Paths.get(filePath).toAbsolutePath().normalize().toString(),
                ScriptSource.fileSystem());
    }

    /**
     * Loads the script at {@code location} and, recursively, what it imports, reading them all
     * through {@code source}.
     */
    public LoomScript load(String location, ScriptSource source) throws IOException {
        visitedFiles.clear();
        return loadRecursive(location, source);
    }

    private LoomScript loadRecursive(String path, ScriptSource source) throws IOException {
        if (visitedFiles.contains(path)) {
            throw new RuntimeException("Circular dependency detected: " + path);
        }

        visitedFiles.add(path);
        log.info("Loading Loom script: " + path);

        String content = source.read(path);
        Lexer lexer = new Lexer(content);
        LoomParser parser = new LoomParser(lexer.tokenize());
        LoomScript script = parser.parseScript();

        // Process imports
        LoomScript effectiveScript = new LoomScript();

        for (String importPathRaw : script.getImports()) {
            if (LibraryImports.isLibrary(importPathRaw)) {
                effectiveScript.merge(LibraryImports.load(importPathRaw));
                continue;
            }
            LoomScript importedScript = loadRecursive(source.resolve(path, importPathRaw), source);
            effectiveScript.merge(importedScript);
        }

        // Merge original script definitions after imports (so original file can override if needed?
        // Actually, for now we just merge them. Order might matter for collisions.)
        effectiveScript.merge(script);
        effectiveScript.applyAgentLadders();
        effectiveScript.applyTrustLadders();
        // The prompts folder is the importing file's own business: imports never choose it (merge
        // leaves it alone).
        effectiveScript.setPromptsDir(script.getPromptsDir(), script.getPromptsDirLine());

        visitedFiles.remove(path);
        return effectiveScript;
    }
}
