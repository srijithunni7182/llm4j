package io.github.llm4j.loom.security;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.ast.McpServerDef;
import java.util.Locale;
import java.util.Map;

/**
 * What one tool of an MCP server does to the world.
 *
 * <p>Only the developer's {@code effects:} on the server decides. A tool nobody described is taken
 * to change things, because the audit cannot start the server and the specification says a client
 * must treat a tool's annotations as untrusted unless it trusts the server. The annotations are
 * therefore a proposal for a person to approve ({@link #propose}, shown by {@code weave mcp list}
 * and written into the script by {@code --write}), never an effect the run acts on by itself.
 */
public final class McpEffects {

    private McpEffects() {}

    /**
     * The effect of {@code tool} on {@code server}.
     *
     * @param server the server as declared in the script
     * @param tool the tool's name
     * @param listed the tool as the server listed it ({@code tools/list}), or null when the server
     *     could not be asked; only used to say in the reason what the server claims
     */
    public static Resolved resolve(McpServerDef server, String tool, Map<String, Object> listed) {
        TaskEffect declared = server.getToolEffects().get(tool);
        if (declared != null)
            return new Resolved(
                    declared,
                    Capabilities.EffectSource.DECLARED,
                    "effects: { "
                            + tool
                            + ": "
                            + declared.name().toLowerCase(Locale.ROOT)
                            + " } declared on mcp "
                            + server.getName());
        String claim =
                listed != null && propose(listed).effect() == TaskEffect.READS
                        ? "; its server annotates it read-only, which is the server's word (run weave mcp"
                                + " list --write to record it once you agree)"
                        : "";
        return new Resolved(
                TaskEffect.CHANGES,
                Capabilities.EffectSource.ASSUMED,
                "assumed changes: no effects: entry for "
                        + tool
                        + " on mcp "
                        + server.getName()
                        + claim
                        + "; declare it with effects: { "
                        + tool
                        + ": reads }");
    }

    /**
     * What the server's annotations suggest, for a person to approve. Follows the specification's
     * defaults: a tool is read-only only when {@code readOnlyHint} is true, and an unset {@code
     * destructiveHint} means destructive. A tool annotated both read-only and destructive is
     * contradicting itself, so the cautious reading wins.
     *
     * @param listed the tool as {@code tools/list} gave it
     */
    public static Proposal propose(Map<String, Object> listed) {
        Object annotations = listed == null ? null : listed.get("annotations");
        Map<?, ?> a = annotations instanceof Map<?, ?> m ? m : Map.of();
        boolean readOnly = Boolean.TRUE.equals(a.get("readOnlyHint"));
        boolean destructive = Boolean.TRUE.equals(a.get("destructiveHint"));
        boolean idempotent = Boolean.TRUE.equals(a.get("idempotentHint"));
        if (readOnly && !destructive)
            return new Proposal(
                    TaskEffect.READS, null, "annotated read-only by the server (its word)");
        String why =
                a.isEmpty()
                        ? "no annotations, so assumed to change things"
                        : destructive
                                ? "annotated destructive by the server"
                                : "not annotated read-only by the server";
        return new Proposal(TaskEffect.CHANGES, idempotent ? "retry" : "skip", why);
    }

    /**
     * What {@code weave mcp list} proposes for one tool.
     *
     * @param effect {@code reads} or {@code changes}
     * @param onUnknown {@code retry} when the server says a repeat is harmless, {@code skip}
     *     otherwise; null for a read
     * @param why the annotations the proposal rests on, as the server's untrusted word
     */
    public record Proposal(TaskEffect effect, String onUnknown, String why) {}

    /**
     * An MCP tool's effect, who decided it and why.
     *
     * @param effect none, reads or changes
     * @param source declared by the developer, or assumed
     * @param reason the explanation shown to the developer
     */
    public record Resolved(TaskEffect effect, Capabilities.EffectSource source, String reason) {}
}
