package io.github.llm4j.loom.tools;

import io.github.llm4j.agent.tool.ToolProfile;

/**
 * What a tool of the author's own reaches, as the author declared it: {@code reach:} on a {@code
 * use: class} tool, or {@code Name.reach} in a {@code .loot} file. The audit never loads or runs
 * such a tool, so it believes only this declaration, and the note says it is the author's word.
 * Without one it assumes the worst.
 */
public final class ReachDeclaration {

    private ReachDeclaration() {}

    /**
     * The profile for a declared reach ({@code none}, {@code reads}, {@code fetches}, {@code
     * writes} or {@code sends}).
     *
     * @param reach the declared word, or null when nothing was declared
     * @param who what the tool is, for the note (e.g. {@code Java class com.acme.Tool})
     */
    public static ToolProfile profile(String reach, String who) {
        if (reach == null)
            return ToolProfile.unknown(
                    who
                            + ": the audit can't see what it does (say what it reaches with reach:"
                            + " none | reads | fetches | writes | sends)");
        String by =
                who
                        + ", declared reach: "
                        + reach
                        + " (the author's word; the audit does not check it)";
        return switch (reach) {
            case "none" -> ToolProfile.of(false, false, false, false, by);
            case "reads" -> ToolProfile.of(false, true, false, false, by);
            case "fetches" -> ToolProfile.of(true, false, false, false, by);
            case "writes" -> ToolProfile.of(false, true, false, true, by);
            case "sends" -> ToolProfile.of(false, true, true, true, by);
            default -> ToolProfile.unknown(
                    who
                            + ": reach: "
                            + reach
                            + " is not one of none, reads, fetches, writes, sends");
        };
    }
}
