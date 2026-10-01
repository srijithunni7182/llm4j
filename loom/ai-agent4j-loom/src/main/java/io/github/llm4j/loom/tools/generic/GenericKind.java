package io.github.llm4j.loom.tools.generic;

import io.github.llm4j.agent.Tool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.github.llm4j.loom.tools.ToolKind;

/**
 * Base of the generic tool kinds: options are validated through {@link Options} (so a bad value is a load
 * error naming the option), and the tool is built from the same parsed values.
 */
public abstract class GenericKind implements ToolKind {

    /** Throws {@link OptionException} for anything wrong with the options; touches no network. */
    protected abstract void validate(Options options, Path baseDir);

    protected abstract Tool build(String name, Options options, Path baseDir, EffectContext context) throws Exception;

    @Override
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

    @Override
    public final Tool create(String name, Map<String, String> options, Path baseDir, EffectContext context) throws Exception {
        return build(name, new Options(options), baseDir, context);
    }

    @Override
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
