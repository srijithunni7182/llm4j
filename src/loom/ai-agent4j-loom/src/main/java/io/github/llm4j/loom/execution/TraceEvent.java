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

    /** A top-level run (or a resumed one) begins: {@code data} has {@code workflow}, {@code run} (the run id, when there is one) and {@code resumed}. */
    public static final String RUN_START = "run_start";
    /** A top-level run leaves {@code executeWorkflow}: {@code data.status} is completed, suspended, stopped, budget or failed. */
    public static final String RUN_END = "run_end";
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
    /** An observer's control on the run: {@code data.action} is pause, resume, suspend or stop; {@code data.state} is requested, held, resumed, applied or cancelled; {@code data.by} says who. */
    public static final String CONTROL = "control";
    /** A {@code sleep} step: {@code data.state} is "start" (with {@code wakeAt} and {@code millis}) or "end". */
    public static final String SLEEP = "sleep";
    public static final String TOOL = "tool";
    public static final String CHECKPOINT = "checkpoint";
    public static final String REWIND = "rewind";
    public static final String DECISION = "decision";
    /** A {@code note} step: a message the workflow wants shown (a result, a status). */
    public static final String NOTE = "note";
    public static final String TASK_START = "task_start";
    public static final String TASK_END = "task_end";
    public static final String TASK_REPLAYED = "task_replayed";
}
