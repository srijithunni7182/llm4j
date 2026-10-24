package io.github.llm4j.loom.execution;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Where a script and the scripts it imports are read from. The default reads files; a host that
 * keeps scripts inside a jar or a database supplies its own, so {@code import "lib/agents.loom"}
 * works wherever the importing script is.
 */
public interface ScriptSource {

    /** The text of the script at {@code location}. */
    String read(String location) throws IOException;

    /** The location of {@code imported}, as written in the script at {@code from}. */
    String resolve(String from, String imported);

    /**
     * Files on disk: a location is a path, and an import is relative to the importing file's
     * folder.
     */
    static ScriptSource fileSystem() {
        return new ScriptSource() {
            @Override
            public String read(String location) throws IOException {
                return Buffers.read(Path.of(location));
            }

            @Override
            public String resolve(String from, String imported) {
                return Path.of(from).getParent().resolve(imported).normalize().toString();
            }
        };
    }
}
