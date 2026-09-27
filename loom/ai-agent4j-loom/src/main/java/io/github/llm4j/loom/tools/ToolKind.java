package io.github.llm4j.loom.tools;

import io.github.llm4j.agent.Tool;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * A kind of tool a script can declare with {@code use: <name>}: which options it takes, which of them are
 * secrets (only allowed from the environment), and how to build it once options are resolved.
 */
public interface ToolKind {

    String name();

    default Set<String> required() {
        return Set.of();
    }

    default Set<String> optional() {
        return Set.of();
    }

    /** Options that hold credentials: must be written {@code env.NAME}. */
    default Set<String> secrets() {
        return Set.of();
    }

    /** Extra checks on the resolved options (e.g. exactly one of two); returns a problem or null. */
    default String check(Map<String, String> options) {
        return null;
    }

    /**
     * @param options values with env references already resolved
     * @param baseDir where relative paths are resolved (the script's directory)
     */
    Tool create(String name, Map<String, String> options, Path baseDir) throws Exception;
}
