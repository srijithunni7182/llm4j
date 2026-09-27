package io.github.llm4j.loom.execution;

import java.time.Instant;
import java.util.Map;

/**
 * Something that happened during a run, as it happens: a delegate starting or ending, an agent's
 * thought, tool call or observation, spend, an approval, memory recall, a guard finding, a pause.
 *
 * @param type one of the constants below
 * @param agent the agent involved, or null
 * @param step the step id ({@code main/s0/f1}), or "" outside a step
 * @param text a human-readable summary
 * @param data details (never secrets)
 */
public record TraceEvent(String type, String agent, String step, String text, Map<String, Object> data, Instant at) {

    public static final String DELEGATE_START = "delegate_start";
    public static final String DELEGATE_END = "delegate_end";
    public static final String DELEGATE_REPLAYED = "delegate_replayed";
    public static final String THOUGHT = "thought";
    public static final String ACTION = "action";
    public static final String OBSERVATION = "observation";
    public static final String BUDGET = "budget";
    public static final String APPROVAL = "approval";
    public static final String MEMORY = "memory";
    public static final String GUARD = "guard";
    public static final String VOICE = "voice";
    public static final String SUSPENDED = "suspended";
}
