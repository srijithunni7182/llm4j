package io.github.llm4j.loom.inspect.otel;

/**
 * Every attribute and event name loom-inspect puts on the wire, in one place. The {@code gen_ai.*} names come from
 * OpenTelemetry's GenAI semantic conventions, which are still in development: when they change, this is the one file that does.
 * Everything Loom-specific is under {@code loom.*}.
 */
public final class Semconv {

    private Semconv() { }

    // ---- OpenTelemetry resource and general ----
    public static final String SERVICE_NAME = "service.name";
    public static final String SERVICE_INSTANCE_ID = "service.instance.id";
    public static final String SERVICE_VERSION = "service.version";
    public static final String HOST_NAME = "host.name";
    public static final String PROCESS_PID = "process.pid";
    public static final String TELEMETRY_SDK_NAME = "telemetry.sdk.name";
    public static final String EVENT_NAME = "event.name";
    public static final String ERROR_TYPE = "error.type";

    // ---- OpenTelemetry GenAI conventions (development) ----
    public static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";
    public static final String GEN_AI_AGENT_NAME = "gen_ai.agent.name";
    public static final String GEN_AI_TOOL_NAME = "gen_ai.tool.name";
    public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
    public static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    public static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    public static final String OP_INVOKE_AGENT = "invoke_agent";
    public static final String OP_EXECUTE_TOOL = "execute_tool";

    // ---- Loom attributes ----
    public static final String LOOM_RUN_ID = "loom.run.id";
    public static final String LOOM_WORKFLOW = "loom.workflow";
    public static final String LOOM_STEP = "loom.step";
    public static final String LOOM_SEQ = "loom.seq";
    public static final String LOOM_SPAN_KIND = "loom.span.kind";
    public static final String LOOM_RESUMED = "loom.run.resumed";
    public static final String LOOM_RUN_STATUS = "loom.run.status";
    public static final String LOOM_SUSPEND_REASON = "loom.suspend.reason";
    public static final String LOOM_RESUME_AT = "loom.resume_at";
    public static final String LOOM_SLEEP_WAKE_AT = "loom.sleep.wake_at";
    public static final String LOOM_USAGE_TOTAL_TOKENS = "loom.usage.total_tokens";
    public static final String LOOM_USAGE_CALLS = "loom.usage.calls";
    public static final String LOOM_COST_USD = "loom.cost.usd";
    public static final String LOOM_TASK = "loom.task";
    public static final String LOOM_TASK_OUTCOME = "loom.task.outcome";
    public static final String LOOM_TASK_ATTEMPTS = "loom.task.attempts";
    /** A task or delegate failed: its kind, name, failure category, attempts, and whether {@code on_failure} took over. */
    public static final String EV_FAILURE = "loom.failure";
    public static final String LOOM_FAILURE_KIND = "loom.failure.kind";
    public static final String LOOM_FAILURE_NAME = "loom.failure.name";
    public static final String LOOM_FAILURE_CATEGORY = "loom.failure.category";
    public static final String LOOM_FAILURE_ATTEMPTS = "loom.failure.attempts";
    public static final String LOOM_FAILURE_HANDLED = "loom.failure.handled";
    public static final String LOOM_FAILURE_ERROR = "loom.failure.error";
    /** On the run's end: the task or delegate that broke the run. */
    public static final String LOOM_RUN_FAILED_KIND = "loom.run.failed_kind";
    public static final String LOOM_RUN_FAILED_NAME = "loom.run.failed_name";
    public static final String LOOM_RUN_FAILED_STEP = "loom.run.failed_step";
    public static final String LOOM_RUN_FAILED_CATEGORY = "loom.run.failed_category";
    public static final String LOOM_TEXT = "loom.text";
    /** A tool call that got no result: the runtime blocked it as an exact repeat of an earlier one. */
    public static final String LOOM_TOOL_NO_RESULT = "loom.tool.no_result";
    /** Short hash of a tool call (tool and input), computed in the process so a loop can be seen even when no text leaves it. */
    public static final String LOOM_ACTION_HASH = "loom.action.hash";
    /** Short hash of an agent's answer, for the same reason. */
    public static final String LOOM_OUTPUT_HASH = "loom.output.hash";
    /** How many distinct figures an answer states, and how many of them appear in nothing the step was given or fetched. */
    public static final String LOOM_GROUNDING_FIGURES = "loom.grounding.figures";
    public static final String LOOM_GROUNDING_UNGROUNDED = "loom.grounding.ungrounded";
    /** A few of the ungrounded figures, for the evidence line. Absent at the metadata detail level. */
    public static final String LOOM_GROUNDING_EXAMPLES = "loom.grounding.examples";

    // ---- Model calls, tool outcomes, context and compaction (Phase 4) ----
    public static final String OP_CHAT = "chat";
    public static final String GEN_AI_RESPONSE_MODEL = "gen_ai.response.model";
    public static final String GEN_AI_RESPONSE_FINISH_REASONS = "gen_ai.response.finish_reasons";
    public static final String LOOM_MODEL_ATTEMPTS = "loom.model.attempts";
    public static final String LOOM_MODEL_WAITED_MS = "loom.model.waited_ms";
    public static final String LOOM_MODEL_FAILOVERS = "loom.model.failovers";
    public static final String LOOM_MODEL_TRUNCATED = "loom.model.truncated";
    public static final String LOOM_MODEL_DURATION_MS = "loom.model.duration_ms";
    public static final String LOOM_TOOL_OUTCOME = "loom.tool.outcome";
    /** The legs of the lethal trifecta a tool touches (a sorted list of outward, private, untrusted; empty for none) and whether that is known or assumed. */
    /** What the injection scan found in an untrusted tool's result: the sorted group names (override, role, exfil, reveal, hidden) and how many matches; never the text. */
    public static final String LOOM_INJECTION_PATTERNS = "loom.injection.patterns";
    public static final String LOOM_INJECTION_COUNT = "loom.injection.count";
    public static final String LOOM_TOOL_REACH = "loom.tool.reach";
    public static final String LOOM_TOOL_REACH_KNOWN = "loom.tool.reach_known";
    public static final String LOOM_TOOL_SOFT_FAILURE = "loom.tool.soft_failure";
    public static final String LOOM_TOOL_RESULT_CHARS = "loom.tool.result_chars";
    public static final String LOOM_TOOL_APPROVAL_MS = "loom.tool.approval_ms";
    public static final String LOOM_TOOL_DURATION_MS = "loom.tool.duration_ms";
    public static final String LOOM_CONTEXT_USED = "loom.context.used";
    public static final String LOOM_CONTEXT_WINDOW = "loom.context.window";
    public static final String LOOM_CONTEXT_USABLE = "loom.context.usable";
    public static final String LOOM_CONTEXT_FRACTION = "loom.context.fraction";
    public static final String LOOM_CONTEXT_SOURCE = "loom.context.source";
    public static final String LOOM_COMPACTION_STRATEGY = "loom.compaction.strategy";
    public static final String LOOM_COMPACTION_OUTCOME = "loom.compaction.outcome";
    public static final String LOOM_COMPACTION_REASON = "loom.compaction.reason";
    public static final String LOOM_COMPACTION_BEFORE = "loom.compaction.before";
    public static final String LOOM_COMPACTION_AFTER = "loom.compaction.after";
    public static final String LOOM_COMPACTION_MESSAGES_BEFORE = "loom.compaction.messages_before";
    public static final String LOOM_COMPACTION_MESSAGES_AFTER = "loom.compaction.messages_after";
    public static final String LOOM_COMPACTION_SUMMARY_TOKENS = "loom.compaction.summary_tokens";
    public static final String LOOM_COMPACTION_SUMMARY_COST = "loom.compaction.summary_cost";
    public static final String LOOM_RUN_MODEL_CALLS = "loom.run.model_calls";
    public static final String LOOM_RUN_TOOL_CALLS = "loom.run.tool_calls";
    public static final String LOOM_RUN_TOOL_FAILURES = "loom.run.tool_failures";
    public static final String LOOM_RUN_RETRIES = "loom.run.retries";
    public static final String LOOM_RUN_FAILOVERS = "loom.run.failovers";
    public static final String LOOM_RUN_COMPACTIONS = "loom.run.compactions";

    // ---- Values of loom.span.kind ----
    public static final String KIND_WORKFLOW = "workflow";
    public static final String KIND_AGENT = "agent";
    public static final String KIND_TOOL = "tool";
    public static final String KIND_TASK = "task";
    public static final String KIND_SLEEP = "sleep";
    public static final String KIND_MODEL = "model";

    // ---- Log record event names ----
    public static final String EV_RUN_START = "loom.run.start";
    public static final String EV_RUN_END = "loom.run.end";
    public static final String EV_RUN_SUSPENDED = "loom.run.suspended";
    public static final String EV_SPAN_START = "loom.span.start";
    public static final String EV_THOUGHT = "loom.thought";
    public static final String EV_OBSERVATION = "loom.observation";
    public static final String EV_BUDGET = "loom.budget";
    /** A workflow variable read at the boundary between two loop rounds, for the no_progress rule. */
    /** An observer's control on the run (pause, resume, suspend, stop) and what became of it. */
    public static final String EV_CONTROL = "loom.control";
    public static final String LOOM_CONTROL_ACTION = "loom.control.action";
    public static final String LOOM_CONTROL_STATE = "loom.control.state";
    public static final String LOOM_CONTROL_BY = "loom.control.by";
    public static final String EV_INSPECT_SAMPLE = "loom.inspect.sample";
    public static final String LOOM_INSPECT_AGENT = "loom.inspect.agent";
    public static final String LOOM_INSPECT_VAR = "loom.inspect.variable";
    public static final String LOOM_INSPECT_LOOP = "loom.inspect.loop";
    public static final String LOOM_INSPECT_HASH = "loom.inspect.hash";
    public static final String EV_NOTE = "loom.note";
    public static final String EV_CONTEXT = "loom.context";
    public static final String EV_COMPACTION = "loom.compaction";
    /** A guard acted: {@code loom.data.event} says which (pii_masked, pii_blocked, pii_detected, bias_detected, bias_blocked). */
    public static final String EV_GUARD = "loom.guard";
    /** Prefix for any other Loom trace event: {@code loom.approval}, {@code loom.rewind}, {@code loom.guard}, … */
    public static final String EV_PREFIX = "loom.";

    // ---- Run statuses (values of loom.run.status) ----
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_SUSPENDED = "suspended";
    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_BUDGET = "budget";
    /** The workflow's own {@code halt} ended the run: shown as stopped, with the reason in the run's trace. */
    public static final String STATUS_HALTED = "halted";
    public static final String STATUS_FAILED = "failed";
}
