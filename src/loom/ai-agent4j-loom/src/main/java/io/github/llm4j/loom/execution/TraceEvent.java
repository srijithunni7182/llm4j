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
    /** A top-level run leaves {@code executeWorkflow}: {@code data.status} is completed, suspended, stopped, budget or failed; {@code model_calls}, {@code tool_calls}, {@code tool_failures}, {@code retries}, {@code failovers} and {@code compactions} are the run's totals so far. */
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
    /** Someone steered, paused, resumed, wrapped up or cancelled a running agent (data: action, step, origin, reason). */
    public static final String INTERVENTION = "intervention";
    /** A step whose agent was stopped, and what the run decided to do about it (data: reason, phase, outcome, changed, why). */
    public static final String STEP_STOPPED = "step_stopped";
    public static final String DECISION = "decision";
    /** A {@code note} step: a message the workflow wants shown (a result, a status). */
    public static final String NOTE = "note";
    /** An agent's context use after a model call: {@code data} has {@code used} (tokens), {@code source} (REPORTED or ESTIMATED) and, when the model's window is known, {@code window}, {@code usable} and {@code fraction}. */
    public static final String CONTEXT = "context";
    /** A compaction was tried: {@code data} has {@code strategy}, {@code outcome}, {@code reason}, {@code before} and {@code after} (tokens), {@code messages_before} and {@code messages_after}, and, when a model wrote notes, {@code summary_tokens} and {@code summary_cost}. Numbers and names only. */
    public static final String COMPACTION = "compaction";
    /** One model call: {@code model}, {@code duration_ms}, {@code waited_ms}, {@code attempts}, {@code failovers}, token counts, {@code finish_reason}, {@code truncated}, {@code outcome} and, for a failure, {@code category} and {@code error_type}. */
    public static final String MODEL_CALL = "model_call";
    /** One tool call: {@code tool}, {@code outcome}, {@code duration_ms}, {@code approval_ms}, {@code result_chars}, {@code soft_failure}, {@code reach} (the legs of the lethal trifecta the tool touches: a sorted list of {@code outward}, {@code private}, {@code untrusted}; empty for none), {@code reach_known} (false when the reach is assumed) and, for a failure, {@code category}. */
    public static final String TOOL_CALL = "tool_call";
    /** A task or delegate failed: {@code kind} (task or delegate), {@code name}, {@code step}, {@code category}, {@code error_type}, {@code attempts}, {@code handled} (true when its {@code on_failure} took over; false when the run fails) and {@code error}. */
    public static final String FAILURE = "failure";
    /** An agent's answer did not match its {@code output_schema}: {@code data} has {@code attempt} and {@code problems}. The step is retried with a correction. */
    public static final String SCHEMA_VIOLATION = "schema_violation";
    /** A script's {@code halt} ended the run: {@code text} and {@code data.reason} say why. */
    public static final String HALT = "halt";
    /**
     * One {@code intentclassify} step: {@code classifier}, {@code step}, {@code backend}, {@code model}, {@code requested_model}, {@code calibrated}, {@code samples},
     * {@code questions}, {@code input_tokens}, {@code output_tokens}, {@code duration_ms}, {@code attempts}, {@code outcome} (ok, unsure, asked or replayed), {@code labels}
     * (the answer to each question), {@code confidence} (the lowest among the choose questions), {@code escalated}. Never the state or a rationale.
     */
    public static final String CLASSIFY = "classify";
    public static final String TASK_START = "task_start";
    public static final String TASK_END = "task_end";
    public static final String TASK_REPLAYED = "task_replayed";
}
