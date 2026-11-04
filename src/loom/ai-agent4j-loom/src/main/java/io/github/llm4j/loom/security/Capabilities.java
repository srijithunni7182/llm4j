package io.github.llm4j.loom.security;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.ast.ToolDef;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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

    static final Set<String> SEARCH =
            Set.of("duckduckgo", "serpapi", "google_search", "web_search");
    static final Set<String> HARMLESS =
            Set.of(
                    "calculator",
                    "datetime",
                    "current_time",
                    "translate",
                    "transliterate",
                    "detect_language",
                    "speak",
                    "transcribe");

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

    /** A built-in tool used by name, or one the host registered (null declaration). */
    static Capabilities ofName(String name) {
        if (SEARCH.contains(name))
            return new Capabilities(true, false, false, false, true, "web search results");
        if (HARMLESS.contains(name)) return none("built-in");
        return unknown("registered by the host (.loot or Java): the audit can't see what it does");
    }

    static Capabilities of(ToolDef t) {
        String kind = t.getKind() == null ? "" : t.getKind().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "duckduckgo", "serpapi", "google_search" -> new Capabilities(
                    true, false, false, false, true, "web search results");
            case "calculator",
                    "datetime",
                    "current_time",
                    "translate",
                    "transliterate",
                    "detect_language",
                    "speak",
                    "transcribe" -> none(kind);
            case "webhook" -> new Capabilities(
                    false, false, true, true, true, "posts to a fixed URL");
            case "email" -> new Capabilities(
                    false,
                    false,
                    true,
                    true,
                    true,
                    opt(t, "to") != null
                            ? "mails fixed recipients"
                            : "mails recipients the agent chooses from allow_to");
            case "http" -> {
                boolean writes = writes(opt(t, "methods"));
                yield new Capabilities(
                        true,
                        opt(t, "auth_value") != null || opt(t, "auth_header") != null,
                        writes,
                        writes,
                        true,
                        writes
                                ? "calls " + opt(t, "base_url") + " with " + opt(t, "methods")
                                : "reads from " + opt(t, "base_url"));
            }
            case "file" -> {
                String mode = opt(t, "mode") == null ? "read" : opt(t, "mode");
                boolean reads = mode.contains("read");
                boolean writes = !mode.equals("read");
                yield new Capabilities(
                        false,
                        reads,
                        false,
                        writes,
                        true,
                        mode + " in " + (opt(t, "root") == null ? "." : opt(t, "root")));
            }
            case "shell" -> new Capabilities(
                    false, true, true, true, true, "runs " + opt(t, "allow"));
            case "sql" -> new Capabilities(false, true, false, false, true, "read-only queries");
            case "openapi" -> new Capabilities(
                    true, true, true, true, true, "any operation in " + opt(t, "spec"));
            case "knowledge_graph" -> new Capabilities(
                    false,
                    true,
                    false,
                    !"true".equals(opt(t, "read_only")),
                    true,
                    "a knowledge graph");
            case "skill_registry" -> new Capabilities(
                    true, false, false, false, true, "skills fetched from " + opt(t, "url"));
            case "class" -> declared(t);
            default -> unknown("tool kind \"" + kind + "\" is not one the audit knows");
        };
    }

    /**
     * A tool of the author's own. The audit never loads or runs it, so it believes only what the
     * script says: {@code reach:} in the tool's entry. Without one it assumes the worst. A
     * declaration is the author's word, and the note says so.
     */
    private static Capabilities declared(ToolDef t) {
        return fromReach(opt(t, "reach"), "Java class " + opt(t, "class"));
    }

    /**
     * The capabilities a tool's author declared ({@code reach:} in the script, or {@code
     * Name.reach} in the .loot file); null reach means undeclared.
     */
    static Capabilities fromReach(String reach, String cls) {
        if (reach == null)
            return unknown(
                    cls
                            + ": the audit can't see what it does (say what it reaches with reach: none | reads | fetches | writes | sends)");
        String by =
                cls
                        + ", declared reach: "
                        + reach
                        + " (the author's word; the audit does not check it)";
        return switch (reach) {
            case "none" -> new Capabilities(false, false, false, false, true, by);
            case "reads" -> new Capabilities(false, true, false, false, true, by);
            case "fetches" -> new Capabilities(true, false, false, false, true, by);
            case "writes" -> new Capabilities(false, true, false, true, true, by);
            case "sends" -> new Capabilities(false, true, true, true, true, by);
            default -> unknown(
                    cls
                            + ": reach: "
                            + reach
                            + " is not one of none, reads, fetches, writes, sends");
        };
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
