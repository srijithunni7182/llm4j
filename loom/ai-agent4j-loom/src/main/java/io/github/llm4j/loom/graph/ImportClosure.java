package io.github.llm4j.loom.graph;

import io.github.llm4j.loom.ast.LoomScript;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * An entry script and every script reachable through {@code import}, each kept as its own parsed script.
 *
 * @param entry the entry file; present in {@code scripts} unless it could not be read
 * @param scripts every file that parsed, in the order their definitions are merged at run time: the imports of a
 *     file come before the file itself
 * @param imports for each file in {@code scripts}, the files it imports directly that are also in {@code scripts}
 * @param discovery the same files, entry first, in the order they were reached
 * @param diagnostics missing files, syntax errors and cycles met on the way
 */
public record ImportClosure(
        Path entry,
        Map<Path, LoomScript> scripts,
        Map<Path, List<Path>> imports,
        List<Path> discovery,
        List<Diagnostic> diagnostics) {

    public boolean hasEntry() {
        return scripts.containsKey(entry);
    }
}
