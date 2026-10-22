package io.github.llm4j.loom.security;

import io.github.llm4j.loom.ast.ToolDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which legs of the "lethal trifecta" a tool touches: it reads content the operator does not
 * control ({@code untrusted}), reaches the operator's own data ({@code privateData}), or sends or
 * changes something beyond the run ({@code outward}).
 *
 * <p>The static audit ({@link SecurityAudit}) and the run's trace both read it from here, so a
 * review of a script and the record of a run cannot disagree about what a tool can do. A tool
 * nobody can see into (a Java class with no declared reach, an MCP server, one the host registered)
 * is assumed to touch all three, with {@code known} false so the assumption can be told from a
 * fact.
 *
 * @param known false when the reach is the cautious assumption, not something the script, the
 *     {@code .loot} file or a built-in said
 */
public record ToolReach(boolean untrusted, boolean privateData, boolean outward, boolean known) {

    /**
     * The reach of the tool called {@code toolName}: the script's own declaration in {@code
     * scriptTools}, else {@code lootReach} ({@code Name.reach = reads} in the {@code .loot} file),
     * else a built-in by name, else the cautious assumption.
     */
    public static ToolReach resolve(
            String toolName, Map<String, ToolDef> scriptTools, Map<String, String> lootReach) {
        Capabilities c =
                Capabilities.resolve(
                        toolName,
                        scriptTools == null ? Map.of() : scriptTools,
                        lootReach == null ? Map.of() : lootReach);
        return new ToolReach(c.untrusted(), c.privateData(), c.outward(), c.known());
    }

    /**
     * The legs as a sorted, comma-separated list ({@code outward,private,untrusted}); empty for a
     * tool that reaches none.
     */
    public String legs() {
        List<String> out = new ArrayList<>(3);
        if (outward) out.add("outward");
        if (privateData) out.add("private");
        if (untrusted) out.add("untrusted");
        return String.join(",", out);
    }
}
