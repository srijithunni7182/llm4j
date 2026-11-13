package io.github.llm4j.loom.security;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.tools.ReachDeclaration;
import io.github.llm4j.loom.tools.ToolFactory;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What a tool lets an agent do, in the three terms that make up the "lethal trifecta": read content
 * it can't trust, reach private data, and send or change something beyond the run. Read from the
 * tool's kind and options; a tool the audit can't see into (a Java class, an MCP server, one
 * registered by the host) is assumed to do all three.
 *
 * @param untrusted reads content from outside (the web, an API, a third-party server)
 * @param privateData reaches data that is yours (files, a database, indexed documents)
 * @param outward sends or changes something beyond the run (a message, a request that writes, a
 *     program)
 * @param effect the call changes something, so it should be approved (outward, or a local write)
 * @param known the audit knows this kind of tool
 * @param level the same fact in the three words a task uses: none, reads or changes
 * @param effectSource whether the developer declared the effect ({@code effect:}, or {@code reach:}
 *     on a class tool) or Loom assumed it from the kind of tool
 * @param effectReason why the effect is what it is, always pointing at {@code effect:} when it was
 *     assumed
 */
public record Capabilities(
        boolean untrusted,
        boolean privateData,
        boolean outward,
        boolean effect,
        boolean known,
        String note,
        TaskEffect level,
        EffectSource effectSource,
        String effectReason) {

    /** Who decided a tool's effect. */
    public enum EffectSource {
        /** The developer wrote it ({@code effect:}, or {@code reach:} on a class tool). */
        DECLARED,
        /** Loom assumed it from the kind of tool, or the cautious default. */
        ASSUMED
    }

    /** A tool whose effect is assumed from its flags: the form every built-in kind uses. */
    public Capabilities(
            boolean untrusted,
            boolean privateData,
            boolean outward,
            boolean effect,
            boolean known,
            String note) {
        this(
                untrusted,
                privateData,
                outward,
                effect,
                known,
                note,
                effect
                        ? TaskEffect.CHANGES
                        : untrusted || privateData ? TaskEffect.READS : TaskEffect.NONE,
                EffectSource.ASSUMED,
                note);
    }

    /** The same capabilities with the effect the developer declared; the legs are unchanged. */
    Capabilities declaring(TaskEffect declared, String reason) {
        return new Capabilities(
                untrusted,
                privateData,
                outward,
                declared == TaskEffect.CHANGES,
                known,
                note,
                declared,
                EffectSource.DECLARED,
                reason);
    }

    /** The effect of {@code operation} of a tool that holds several, else the tool's own. */
    public static TaskEffect operationEffect(ToolDef def, String operation, Capabilities whole) {
        TaskEffect declared = def.getOperationEffects().get(operation);
        return declared != null ? declared : whole.level();
    }

    static Capabilities none(String note) {
        return new Capabilities(false, false, false, false, true, note);
    }

    static Capabilities unknown(String note) {
        return new Capabilities(true, true, true, true, false, note);
    }

    /**
     * What a tool the run or the audit meets by name reaches: the script's own declaration, else
     * what the {@code .loot} file declares, else a built-in by name, else the worst. The one place
     * both the static audit and the trace ask, so they cannot disagree.
     */
    public static Capabilities resolve(
            String toolName,
            java.util.Map<String, ToolDef> scriptTools,
            java.util.Map<String, String> lootReach) {
        ToolDef def = scriptTools.get(toolName);
        if (def != null) return withDeclaration(def, of(def));
        if (lootReach.containsKey(toolName)) {
            Capabilities c =
                    fromReach(lootReach.get(toolName), "Tool " + toolName + " from the .loot file");
            return c.known()
                    ? c.declaring(
                            c.level(),
                            "reach: " + lootReach.get(toolName) + " declared in the .loot file")
                    : c;
        }
        return ofName(toolName);
    }

    /**
     * The developer's {@code effect:} wins over anything assumed (and over {@code reach:}, which
     * the parser refuses to see beside it). {@code reach:} on a class tool is itself a declaration,
     * so its effect counts as declared.
     */
    private static Capabilities withDeclaration(ToolDef def, Capabilities assumed) {
        Optional<TaskEffect> declared = def.getEffect();
        if (declared.isPresent())
            return assumed.declaring(
                    declared.get(),
                    "effect: "
                            + declared.get().name().toLowerCase(Locale.ROOT)
                            + " declared on tool "
                            + def.getName());
        if ("class".equalsIgnoreCase(def.getKind()) && opt(def, "reach") != null && assumed.known())
            return assumed.declaring(
                    assumed.level(),
                    "reach: "
                            + opt(def, "reach")
                            + " declared on tool "
                            + def.getName()
                            + " (the older form; effect: says it directly)");
        return assumed.withReason(reasonFor(def, assumed));
    }

    private Capabilities withReason(String reason) {
        return new Capabilities(
                untrusted, privateData, outward, effect, known, note, level, effectSource, reason);
    }

    private static String reasonFor(ToolDef def, Capabilities c) {
        return "assumed "
                + c.level().name().toLowerCase(Locale.ROOT)
                + " for a "
                + def.getKind()
                + " tool; say what it does with effect: none | reads | changes";
    }

    /**
     * A built-in tool used by name ({@link ToolFactory#BUILT_INS}), or one the host registered (no
     * declaration). A built-in that reaches nothing is noted as such.
     */
    static Capabilities ofName(String name) {
        String kind = ToolFactory.BUILT_INS.get(name);
        java.util.Optional<io.github.llm4j.loom.tools.ToolKind> builtIn =
                kind == null ? java.util.Optional.empty() : ToolFactory.standardKind(kind);
        if (builtIn.isEmpty())
            return unknown(
                    "registered by the host (.loot or Java): the audit can't see what it does");
        ToolProfile p = builtIn.get().profile(Map.of());
        boolean reaches = p.untrusted() || p.privateData() || p.outward();
        return from(reaches || !p.known() ? p : withNote(p, "built-in"));
    }

    /**
     * What a declared tool reaches: its kind's own answer ({@link
     * io.github.llm4j.loom.tools.ToolKind#profile}) from the options as written. A kind Loom does
     * not have is assumed to reach everything.
     */
    static Capabilities of(ToolDef t) {
        return from(profile(t));
    }

    /**
     * The kind's statement about a declared tool, before any {@code effect:} the developer wrote:
     * what it reaches, whether its calls are journaled, its isolation. A kind Loom does not have
     * reaches everything.
     */
    public static ToolProfile profile(ToolDef t) {
        String kind = t.getKind() == null ? "" : t.getKind().toLowerCase(Locale.ROOT);
        return ToolFactory.standardKind(kind)
                .map(k -> k.profile(written(t)))
                .orElseGet(
                        () ->
                                ToolProfile.unknown(
                                        "tool kind \"" + kind + "\" is not one the audit knows"));
    }

    /** The options as written: a reference is its text ({@code env.NAME}), never its value. */
    private static Map<String, String> written(ToolDef t) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        t.getOptions()
                .forEach((name, v) -> out.put(name, v.isReference() ? v.toString() : v.value()));
        return out;
    }

    private static ToolProfile withNote(ToolProfile p, String note) {
        return new ToolProfile(
                p.untrusted(),
                p.privateData(),
                p.outward(),
                p.effect(),
                p.isolation(),
                p.journaled(),
                p.known(),
                note);
    }

    /** The audit's view of a profile: its effect assumed from the kind, the note as the reason. */
    static Capabilities from(ToolProfile p) {
        return new Capabilities(
                p.untrusted(),
                p.privateData(),
                p.outward(),
                p.effect() == TaskEffect.CHANGES,
                p.known(),
                p.note(),
                p.effect(),
                EffectSource.ASSUMED,
                p.note());
    }

    /**
     * The capabilities a tool's author declared ({@code reach:} in the script, or {@code
     * Name.reach} in the .loot file); null reach means undeclared.
     */
    static Capabilities fromReach(String reach, String cls) {
        return from(ReachDeclaration.profile(reach, cls));
    }

    static boolean writes(String methods) {
        if (methods == null) return false;
        for (String m : methods.split(","))
            if (!m.strip().equalsIgnoreCase("GET") && !m.isBlank()) return true;
        return false;
    }

    static String opt(ToolDef t, String name) {
        ToolDef.OptionValue v = t.getOptions().get(name);
        return v == null ? null : (v.isReference() ? v.toString() : v.value());
    }
}
