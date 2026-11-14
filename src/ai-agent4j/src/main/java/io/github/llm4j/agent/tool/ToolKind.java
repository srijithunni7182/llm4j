package io.github.llm4j.agent.tool;

import io.github.llm4j.agent.Tool;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * A kind of tool that is built from plain string options instead of Java code: which options it
 * takes, which of them are secrets, how to check them without side effects, and how to build the
 * tool. A host (a script runtime, a configuration file, a plain Java program) looks kinds up by
 * {@link #name()} and passes the options it has resolved.
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

    /**
     * Options that hold credentials: a host should accept them only from a secret store or the
     * environment.
     */
    default Set<String> secrets() {
        return Set.of();
    }

    /**
     * A rule about how an agent may use a tool of this kind, for a host to check when it wires
     * agents to tools: a kind that runs programs requires the agent to have it approved. Returns a
     * problem or null.
     */
    default String agentProblem(
            Map<String, String> options, String toolName, String agentName, boolean approved) {
        return null;
    }

    /**
     * What a tool of this kind with these options reaches and does, for an audit. {@code options}
     * are as written in the declaration: a reference to the environment or a secret store stays its
     * reference text, never its value. Touches neither the network nor the files.
     *
     * <p>A kind that does not override this is assumed to reach everything and change things
     * ({@link ToolProfile#unknown}): an audit never trusts a kind that has not said.
     */
    default ToolProfile profile(Map<String, String> options) {
        return ToolProfile.unknown(
                "tool kind \""
                        + name()
                        + "\" does not say what it reaches, so it is assumed to"
                        + " reach everything");
    }

    /**
     * Option-name prefixes whose values stay references ({@code secret.NAME}, {@code env.NAME})
     * when the tool is built: the tool resolves them on each call through {@link
     * EffectContext#lookup}, so it never holds the value. A host refuses a literal under them.
     */
    default Set<String> resolvedPerCall() {
        return Set.of();
    }

    /**
     * Options that name another tool of the script, by option, with the kind that tool must be: a
     * {@code sandbox}'s {@code workspace: Data}. A host checks the named tool exists and is of that
     * kind, and passes its options to this one as {@code <option>.<name>}.
     */
    default java.util.Map<String, String> references() {
        return java.util.Map.of();
    }

    /**
     * Whether a host builds a tool of this kind when a run starts rather than at its first use: a
     * kind whose building asks the machine for something it needs (a container runtime) or cleans
     * up after a crashed run (a program a sandbox left serving) says so, and a run then fails or
     * cleans up before any step.
     */
    default boolean buildsAtRunStart() {
        return false;
    }

    /** In {@link #uncheckedResult}: the whole result may hold text a model wrote. */
    String WHOLE_RESULT = "*";

    /**
     * The top-level fields of a tool's result (a JSON object) that may hold text a model wrote,
     * whatever the arguments were: a test name in a report a model's code produced, a page's text,
     * a program's output. A host keeps those fields marked as unchecked model text, so they cannot
     * leave a workflow as if a person or the code had written them; the other fields (counts,
     * statuses) are data the tool parsed. {@link #WHOLE_RESULT} marks the whole result.
     *
     * <p>A kind that names fields here has parsed the rest of its result itself, so a host trusts
     * those other fields whatever the arguments were. The default is none: a host treats the result
     * as the tool's own data, except that a tool that computes only from its arguments (one whose
     * {@link #profile} reaches nothing) gives its result the origin of those arguments.
     */
    default Set<String> uncheckedResult(Map<String, String> options) {
        return Set.of();
    }

    /** What is wrong with the options, or null. Touches neither the network nor the files. */
    String check(Map<String, String> options, Path baseDir);

    /**
     * Builds the tool for use outside a managed run: nothing is audited and its journal lives in
     * memory.
     *
     * @param options values with any environment references already resolved
     * @param baseDir where relative paths are resolved
     */
    Tool create(String name, Map<String, String> options, Path baseDir) throws Exception;

    /**
     * As {@link #create(String, Map, Path)}, for a host that records the run: a durable journal,
     * audit and trace.
     */
    Tool create(String name, Map<String, String> options, Path baseDir, EffectContext context)
            throws Exception;
}
