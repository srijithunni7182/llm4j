package io.github.llm4j.agent.tool;

import io.github.llm4j.agent.task.TaskEffect;
import java.util.Objects;

/**
 * What a tool of some kind reaches and does, as its kind tells it from the tool's options ({@link
 * ToolKind#profile}). An audit reads it to decide what an agent holding the tool can do; it is the
 * kind's own statement, so a new kind needs no change in the audit.
 *
 * @param untrusted reads content from outside that nobody vouches for (the web, an API, a
 *     third-party server)
 * @param privateData reaches data that is the user's own (files, a database, indexed documents)
 * @param outward sends or changes something beyond the run (a message, a request that writes, a
 *     program)
 * @param effect what a call does to the world: {@link TaskEffect#CHANGES} when it should be
 *     approved
 * @param isolation the isolation the tool applies to a program it runs, {@link Isolation#NONE} for
 *     a tool that runs none
 * @param journaled the tool's calls go through the effect journal, so a resumed run does not repeat
 *     them and a test can record them as effects
 * @param known false when the kind could not say: every other field then assumes the worst
 * @param note one line on what the tool reaches, shown in an audit
 */
public record ToolProfile(
        boolean untrusted,
        boolean privateData,
        boolean outward,
        TaskEffect effect,
        Isolation isolation,
        boolean journaled,
        boolean known,
        String note) {

    /** Checks that no fact is missing. */
    public ToolProfile {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(isolation, "isolation");
        Objects.requireNonNull(note, "note");
    }

    /**
     * A known tool with the given reach. Its effect is {@link TaskEffect#CHANGES} when {@code
     * changes}, else {@link TaskEffect#READS} when it reads anything, else {@link TaskEffect#NONE};
     * it runs no program and is not journaled.
     */
    public static ToolProfile of(
            boolean untrusted, boolean privateData, boolean outward, boolean changes, String note) {
        TaskEffect effect =
                changes
                        ? TaskEffect.CHANGES
                        : untrusted || privateData ? TaskEffect.READS : TaskEffect.NONE;
        return new ToolProfile(
                untrusted, privateData, outward, effect, Isolation.NONE, false, true, note);
    }

    /**
     * A tool that computes only from its arguments: it reaches nothing, changes nothing and gives
     * the same answer for the same arguments.
     */
    public static ToolProfile pure(String note) {
        return of(false, false, false, false, note);
    }

    /**
     * What is assumed of a tool whose kind cannot say what it reaches: everything, and that it
     * changes things.
     */
    public static ToolProfile unknown(String note) {
        return new ToolProfile(
                true, true, true, TaskEffect.CHANGES, Isolation.NONE, false, false, note);
    }

    /** The same profile, journaled or not. */
    public ToolProfile journaled(boolean journaled) {
        return new ToolProfile(
                untrusted, privateData, outward, effect, isolation, journaled, known, note);
    }

    /** The same profile with the isolation the tool applies. */
    public ToolProfile isolated(Isolation level) {
        return new ToolProfile(
                untrusted, privateData, outward, effect, level, journaled, known, note);
    }
}
