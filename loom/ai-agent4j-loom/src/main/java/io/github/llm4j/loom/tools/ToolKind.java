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

    /**
     * A rule about how an agent may use a tool of this kind, checked at load: for example, a kind that runs
     * programs requires the agent to have it approved. Returns a problem or null.
     */
    default String agentProblem(Map<String, String> options, String toolName, String agentName, boolean approved) {
        return null;
    }

    /** Option-name prefixes the kind accepts in any spelling after them, e.g. {@code header.}. */
    default Set<String> prefixes() {
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

    /** As {@link #check(Map)}, for kinds that also look at files (relative to the script's directory). */
    default String check(Map<String, String> options, Path baseDir) {
        return check(options);
    }

    /**
     * Values for options the declaration leaves out, usually environment references (a built-in
     * {@code translate} takes its key from {@code SARVAM_API_KEY}). A default whose variable isn't set is
     * simply absent — reported only if the option is required.
     */
    default Map<String, io.github.llm4j.loom.ast.ToolDef.OptionValue> defaults() {
        return Map.of();
    }

    /**
     * @param options values with env references already resolved
     * @param baseDir where relative paths are resolved (the script's directory)
     */
    Tool create(String name, Map<String, String> options, Path baseDir) throws Exception;

    /** As {@link #create(String, Map, Path)}, for kinds that report to the run they're in. */
    default Tool create(String name, Map<String, String> options, Path baseDir,
                        io.github.llm4j.tools.EffectContext context) throws Exception {
        return create(name, options, baseDir);
    }
}
