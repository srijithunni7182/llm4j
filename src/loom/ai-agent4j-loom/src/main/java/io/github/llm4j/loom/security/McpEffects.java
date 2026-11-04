package io.github.llm4j.loom.security;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.ast.McpServerDef;
import java.util.Locale;
import java.util.Map;

/**
 * What one tool of an MCP server does to the world. The developer's {@code effects:} on the server
 * decides; without it the server author's own annotations are used when they were read ({@code
 * readOnlyHint: true} reads; anything else, or no annotations, changes), so a tool nobody described
 * is never taken for harmless.
 */
public final class McpEffects {

    private McpEffects() {}

    /**
     * The effect of {@code tool} on {@code server}.
     *
     * @param server the server as declared in the script
     * @param tool the tool's name
     * @param listed the tool as the server listed it ({@code tools/list}), or null when the server
     *     could not be asked
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
        if (listed == null)
            return assumed(
                    "the server's annotations could not be read (run weave mcp list or connect"
                            + " to it)");
        Object annotations = listed.get("annotations");
        if (!(annotations instanceof Map<?, ?> a) || a.isEmpty())
            return assumed("the server gave " + tool + " no annotations");
        if (Boolean.TRUE.equals(a.get("destructiveHint")))
            return assumed(tool + " is annotated destructive by its server");
        if (Boolean.TRUE.equals(a.get("readOnlyHint")))
            return new Resolved(
                    TaskEffect.READS,
                    Capabilities.EffectSource.ASSUMED,
                    tool
                            + " is annotated read-only by its server (its author's word; declare"
                            + " it under effects: to make it yours)");
        return assumed(tool + " is not annotated read-only by its server");
    }

    private static Resolved assumed(String why) {
        return new Resolved(
                TaskEffect.CHANGES,
                Capabilities.EffectSource.ASSUMED,
                "assumed changes: " + why + "; declare it with effects: { tool: reads }");
    }

    /**
     * An MCP tool's effect, who decided it and why.
     *
     * @param effect none, reads or changes
     * @param source declared by the developer, or assumed
     * @param reason the explanation shown to the developer
     */
    public record Resolved(TaskEffect effect, Capabilities.EffectSource source, String reason) {}
}
