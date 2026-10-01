package io.github.llm4j.loom.tools.generic;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Base of the generic tools. It turns every failure into an {@code Error:} result (a tool never throws
 * at the agent), removes secrets from whatever is returned, and reports each call to the run's audit log
 * and trace.
 *
 * <p>Subclasses implement {@link #run}: throw {@link ToolRefusal} for a call they won't make and
 * {@link UnknownOutcomeException} when an action may have happened.
 */
public abstract class GenericTool implements Effectful {

    private final String name;
    private final String kind;
    private final String description;
    private final Redactor redactor;
    private final EffectContext context;

    protected GenericTool(String name, String kind, String description, Redactor redactor, EffectContext context) {
        this.name = name;
        this.kind = kind;
        this.description = description;
        this.redactor = redactor;
        this.context = context;
    }

    /** Does the work and returns the text the agent sees. */
    protected abstract String run(Map<String, Object> args, String idempotencyKey) throws Exception;

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final String getDescription() {
        return description;
    }

    @Override
    public boolean isEffect(Map<String, Object> args) {
        return false;
    }

    @Override
    public EffectPolicy policy() {
        return EffectPolicy.DEFAULT;
    }

    @Override
    public String target(Map<String, Object> args) {
        return kind;
    }

    @Override
    public final Outcome perform(Map<String, Object> args, String idempotencyKey) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        Outcome outcome;
        try {
            outcome = Outcome.ok(run(safeArgs, idempotencyKey));
        } catch (UnknownOutcomeException e) {
            outcome = Outcome.unknown(e.getMessage());
        } catch (ToolRefusal e) {
            outcome = Outcome.failed(e.getMessage());
        } catch (Exception e) {
            outcome = Outcome.failed(describe(e));
        }
        return new Outcome(redactor.scrub(outcome.text()), outcome.status());
    }

    @Override
    public final String execute(Map<String, Object> args) {
        long started = context.clock().millis();
        Outcome outcome = perform(args, null);
        if (!isEffect(args == null ? Map.of() : args)) {
            report("tool_call", args, outcome.status().name().toLowerCase(Locale.ROOT), context.clock().millis() - started);
        }
        return outcome.text();
    }

    /** Records one call: the tool, its kind, what it touched and how it ended. Never a body or a result. */
    protected final void report(String event, Map<String, Object> args, String outcome, long millis) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", name);
        data.put("kind", kind);
        data.put("step", context.currentStep());
        data.put("target", safeTarget(args));
        data.put("outcome", outcome);
        data.put("millis", millis);
        context.audit(event, data);
        context.trace(event.replace('_', ' ') + ": " + name + " " + data.get("target") + " → " + outcome, data);
    }

    private String safeTarget(Map<String, Object> args) {
        try {
            return redactor.scrub(target(args == null ? Map.of() : args));
        } catch (RuntimeException e) {
            return kind;
        }
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }

    // ── Reading arguments ────────────────────────────────────────────────────────────────────

    /** A required, non-blank text argument. Numbers and booleans are read as text; lists and objects are not text. */
    protected static String text(Map<String, Object> args, String key) {
        String v = optionalText(args, key);
        if (v == null) throw new ToolRefusal(key + " is required");
        return v;
    }

    /** An optional text argument, or null. */
    protected static String optionalText(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        if (!(v instanceof CharSequence || v instanceof Number || v instanceof Boolean)) throw new ToolRefusal(key + " must be text");
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }
}
