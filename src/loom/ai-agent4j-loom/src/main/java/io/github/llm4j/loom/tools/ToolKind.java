package io.github.llm4j.loom.tools;

import io.github.llm4j.agent.Tool;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * A kind of tool a script can declare with {@code use: <name>}: which options it takes, which of
 * them are secrets (only allowed from the environment), and how to build it once options are
 * resolved.
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
     * A rule about how an agent may use a tool of this kind, checked at load: for example, a kind
     * that runs programs requires the agent to have it approved. Returns a problem or null.
     */
    default String agentProblem(
            Map<String, String> options, String toolName, String agentName, boolean approved) {
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

    /**
     * Options that may be written {@code invoker.NAME}: a credential of whoever runs the workflow,
     * asked for on every request instead of read once (see {@link
     * io.github.llm4j.secret.InvokerCredentials}). A kind that lists one must build its tool from
     * the sources it is given in {@link #create(String, Map, Map, Path,
     * io.github.llm4j.agent.tool.EffectContext)}.
     */
    default Set<String> lateBound() {
        return Set.of();
    }

    /**
     * What a tool of this kind with these options reaches and does, for {@code weave audit}, the
     * review, the rewind checks and {@code weave test}. {@code options} are as written in the
     * declaration: a reference stays its reference text ({@code env.NAME}), never its value.
     * Touches neither the network nor the files.
     *
     * <p>A kind that does not override this is assumed to reach everything and change things.
     */
    default io.github.llm4j.agent.tool.ToolProfile profile(Map<String, String> options) {
        return io.github.llm4j.agent.tool.ToolProfile.unknown(
                "tool kind \""
                        + name()
                        + "\" does not say what it reaches, so it is assumed to reach everything");
    }

    /** As {@link io.github.llm4j.agent.tool.ToolKind#resolvedPerCall}. */
    default Set<String> resolvedPerCall() {
        return Set.of();
    }

    /** As {@link io.github.llm4j.agent.tool.ToolKind#references}. */
    default Map<String, String> references() {
        return Map.of();
    }

    /**
     * Whether the run builds a tool of this kind when it starts rather than at its first use (see
     * {@link io.github.llm4j.agent.tool.ToolKind#buildsAtRunStart()}).
     */
    default boolean buildsAtRunStart() {
        return false;
    }

    /**
     * The top-level fields of a tool's result that may hold text a model wrote, as in {@link
     * io.github.llm4j.agent.tool.ToolKind#uncheckedResult}; none by default.
     */
    default Set<String> uncheckedResult(Map<String, String> options) {
        return Set.of();
    }

    /**
     * Extra checks on the resolved options (e.g. exactly one of two); returns a problem or null.
     */
    default String check(Map<String, String> options) {
        return null;
    }

    /**
     * As {@link #check(Map)}, for kinds that also look at files (relative to the script's
     * directory).
     */
    default String check(Map<String, String> options, Path baseDir) {
        return check(options);
    }

    /**
     * Values for options the declaration leaves out, usually environment references (a built-in
     * {@code translate} takes its key from {@code SARVAM_API_KEY}). A default whose variable isn't
     * set is simply absent — reported only if the option is required.
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
    default Tool create(
            String name,
            Map<String, String> options,
            Path baseDir,
            io.github.llm4j.agent.tool.EffectContext context)
            throws Exception {
        return create(name, options, baseDir);
    }

    /**
     * As {@link #create(String, Map, Path, io.github.llm4j.agent.tool.EffectContext)}, with a
     * source for each option written {@code invoker.NAME} (those options are left out of {@code
     * options}). Only kinds that list the option in {@link #lateBound()} get here with a non-empty
     * map.
     */
    default Tool create(
            String name,
            Map<String, String> options,
            Map<String, io.github.llm4j.secret.CredentialSource> lateBound,
            Path baseDir,
            io.github.llm4j.agent.tool.EffectContext context)
            throws Exception {
        if (!lateBound.isEmpty()) {
            throw new IllegalStateException(
                    "use: "
                            + name()
                            + " does not take "
                            + lateBound.keySet()
                            + " from the invoker");
        }
        return create(name, options, baseDir, context);
    }
}
