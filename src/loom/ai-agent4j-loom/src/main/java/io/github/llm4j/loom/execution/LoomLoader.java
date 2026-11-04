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
        LoomScript loaded = loadRecursive(location, source);
        noteTheGateIsBuildTime(loaded);
        return loaded;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean GATE_NOTICE_GIVEN =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile boolean gateNoticeWanted = true;

    /**
     * Says whether loading should mention, once, that the golden-case gate for extractors and
     * evaluators is enforced by {@code weave check} at build time. The {@code weave} command turns
     * it off because it enforces the gate itself.
     */
    public static void setGateNoticeWanted(boolean wanted) {
        gateNoticeWanted = wanted;
        if (wanted) GATE_NOTICE_GIVEN.set(false); // asking again re-arms the single mention
    }

    /**
     * Loading never reads {@code eval/golden}: an application need not ship it. So that the gate
     * is not forgotten, a script with extractors or evaluators says once that it is enforced at
     * build time (docs/specs/28-evaluation-first, R4).
     */
    private static void noteTheGateIsBuildTime(LoomScript script) {
        if (!gateNoticeWanted
                || (script.getExtractors().isEmpty() && script.getEvaluators().isEmpty())) return;
        if (GATE_NOTICE_GIVEN.compareAndSet(false, true))
            log.warning(
                    "This script has extractors or evaluators. Their golden cases are required by"
                            + " `weave check`, not when the script is loaded: run `weave check` over the"
                            + " project in your build.");
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
        // The shared prompt is the importing file's own business too. Merging used to drop it, so a
        // script run through the loader (every weave command) never told its agents the shared
        // text.
        effectiveScript.setSharedPrompt(
                script.getSharedPrompt(),
                script.getSharedPromptRef(),
                script.getSharedPromptLine());

        // So is how it replaces variables: an imported file never chooses it for the importer.
        effectiveScript.setSubstitution(script.getSubstitution());

        visitedFiles.remove(path);
        return effectiveScript;
    }
}
