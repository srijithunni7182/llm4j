package io.github.llm4j.tools;

import io.github.llm4j.agent.Tool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Base of the generic tool kinds: options are validated through {@link Options} (so a bad value is a load
 * error naming the option), and the tool is built from the same parsed values.
 */
public abstract class GenericKind {

    /** The kind's name, as a script spells it in {@code use: <name>}. */
    public abstract String name();

    /** Options that must be given. */
    public Set<String> required() {
        return Set.of();
    }

    /** Options that may be given. */
    public Set<String> optional() {
        return Set.of();
    }

    /** Option-name prefixes the kind accepts in any spelling after them, e.g. {@code header.}. */
    public Set<String> prefixes() {
        return Set.of();
    }

    /** Options that hold credentials: a host should accept them only from a secret store or the environment. */
    public Set<String> secrets() {
        return Set.of();
    }

    /**
     * A rule about how an agent may use a tool of this kind, for a host to check at load: a kind that runs
     * programs requires the agent to have it approved. Returns a problem or null.
     */
    public String agentProblem(Map<String, String> options, String toolName, String agentName, boolean approved) {
        return null;
    }

    /** Throws {@link OptionException} for anything wrong with the options; touches no network. */
    protected abstract void validate(Options options, Path baseDir);

    protected abstract Tool build(String name, Options options, Path baseDir, EffectContext context) throws Exception;

    /** What is wrong with the options, or null. Touches neither the network nor the files. */
    public final String check(Map<String, String> options, Path baseDir) {
        for (String secret : secrets()) {
            String v = options.get(secret);
            if (v != null && v.length() < Redactor.MIN_SECRET_LENGTH) {
                return secret + ": is too short to be a credential (at least " + Redactor.MIN_SECRET_LENGTH + " characters)";
            }
        }
        try {
            validate(new Options(options), baseDir);
            return null;
        } catch (OptionException e) {
            return e.getMessage();
        }
    }

    /** Builds the tool from resolved option values (journaled by {@code context} if it has side effects); {@code baseDir} anchors relative paths. */
    public final Tool create(String name, Map<String, String> options, Path baseDir, EffectContext context) throws Exception {
        Tool tool = build(name, new Options(options), baseDir, context);
        // A tool that has side effects is journaled here, so exactly-once holds wherever the tool is used.
        return tool instanceof Effectful effectful ? new EffectTool(effectful, context) : tool;
    }

    /** As above, for a tool used outside a run: nothing is audited and the journal lives in memory. */
    public final Tool create(String name, Map<String, String> options, Path baseDir) throws Exception {
        return create(name, options, baseDir, EffectContext.noop());
    }

    /** A redactor for this declaration's credentials: its secret options and any header that is a credential. */
    protected final Redactor redactor(Options options, String... extra) {
        List<String> values = new ArrayList<>();
        for (String s : secrets()) if (options.has(s)) values.add(options.get(s));
        options.headers().forEach((name, value) -> { if (Options.isSecretHeader(name)) values.add(value); });
        values.addAll(List.of(extra));
        return new Redactor(values);
    }
}
