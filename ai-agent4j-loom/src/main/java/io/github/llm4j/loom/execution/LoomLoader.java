package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Orchestrates the loading of Loom scripts, resolving imports recursively
 * and detecting circular dependencies.
 */
public class LoomLoader {
    private static final Logger log = Logger.getLogger(LoomLoader.class.getName());

    private final Set<Path> visitedFiles = new HashSet<>();

    public LoomScript load(String filePath) throws IOException {
        Path startPath = Paths.get(filePath).toAbsolutePath().normalize();
        visitedFiles.clear();
        return loadRecursive(startPath);
    }

    private LoomScript loadRecursive(Path path) throws IOException {
        if (visitedFiles.contains(path)) {
            throw new RuntimeException("Circular dependency detected: " + path);
        }

        visitedFiles.add(path);
        log.info("Loading Loom script: " + path);

        String content = Files.readString(path);
        Lexer lexer = new Lexer(content);
        LoomParser parser = new LoomParser(lexer.tokenize());
        LoomScript script = parser.parseScript();

        // Process imports
        LoomScript effectiveScript = new LoomScript();
        
        for (String importPathRaw : script.getImports()) {
            Path importPath = path.getParent().resolve(importPathRaw).normalize();
            LoomScript importedScript = loadRecursive(importPath);
            effectiveScript.merge(importedScript);
        }

        // Merge original script definitions after imports (so original file can override if needed? 
        // Actually, for now we just merge them. Order might matter for collisions.)
        effectiveScript.merge(script);

        visitedFiles.remove(path);
        return effectiveScript;
    }
}
