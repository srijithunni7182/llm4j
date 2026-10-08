package io.github.llm4j.agent.tool;

import io.github.llm4j.agent.Tool;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * A kind of tool that is built from plain string options instead of Java code: which options it takes, which
 * of them are secrets, how to check them without side effects, and how to build the tool. A host (a script
 * runtime, a configuration file, a plain Java program) looks kinds up by {@link #name()} and passes the
 * options it has resolved.
 */
public interface ToolKind {

    /** The name a host's configuration uses for this kind, e.g. {@code webhook}. */
    String name();

    /** Options that must be given. */
    default Set<String> required() {
        return Set.of();
    }

    /** Options that may be given. */
    default Set<String> optional() {
        return Set.of();
    }

    /** Option-name prefixes accepted in any spelling after them, e.g. {@code header.}. */
    default Set<String> prefixes() {
        return Set.of();
    }

    /** Options that hold credentials: a host should accept them only from a secret store or the environment. */
    default Set<String> secrets() {
        return Set.of();
    }

    /**
     * A rule about how an agent may use a tool of this kind, for a host to check when it wires agents to
     * tools: a kind that runs programs requires the agent to have it approved. Returns a problem or null.
     */
    default String agentProblem(Map<String, String> options, String toolName, String agentName, boolean approved) {
        return null;
    }

    /** What is wrong with the options, or null. Touches neither the network nor the files. */
    String check(Map<String, String> options, Path baseDir);

    /**
     * Builds the tool for use outside a managed run: nothing is audited and its journal lives in memory.
     *
     * @param options values with any environment references already resolved
     * @param baseDir where relative paths are resolved
     */
    Tool create(String name, Map<String, String> options, Path baseDir) throws Exception;

    /** As {@link #create(String, Map, Path)}, for a host that records the run: a durable journal, audit and trace. */
    Tool create(String name, Map<String, String> options, Path baseDir, EffectContext context) throws Exception;
}
