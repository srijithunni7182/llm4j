package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.context.ContextFiles;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Option;

/**
 * {@code --context-windows} and {@code --loct}: where each model's context window is configured, and where your own compaction strategies are
 * mapped to classes. Both are optional; a file beside the script ({@code flow.windows}, {@code flow.loct}) is found without them.
 */
final class ContextOptions {

    @Option(names = "--context-windows", paramLabel = "<file>",
            description = "A windows file: 'model = window / reserve' per line (the size of each model's context window). Repeatable; a later file wins. Layered over <script>.windows, ~/.llm4j/context-windows and the built-in catalogue.")
    List<File> windows;

    @Option(names = "--loct", paramLabel = "<file>",
            description = "A .loct file mapping a name to a class of your own compaction strategy ('keep_decisions = com.acme.KeepDecisions'), for use as 'compact: keep_decisions' in a script. Repeatable.")
    List<File> loct;

    /** Remembers the files for the executors this command builds; false (after saying why) when one named is missing. */
    boolean apply(Path script, WeaveEnv env) {
        try {
            ContextFiles.use(script, windows, loct);
            return true;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return false;
        }
    }
}
