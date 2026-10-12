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

    // ---- Values of loom.span.kind ----
    public static final String KIND_WORKFLOW = "workflow";
    public static final String KIND_AGENT = "agent";
    public static final String KIND_TOOL = "tool";
    public static final String KIND_TASK = "task";
    public static final String KIND_SLEEP = "sleep";

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
    /** Prefix for any other Loom trace event: {@code loom.approval}, {@code loom.rewind}, {@code loom.guard}, … */
    public static final String EV_PREFIX = "loom.";

    // ---- Run statuses (values of loom.run.status) ----
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_SUSPENDED = "suspended";
    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_BUDGET = "budget";
    public static final String STATUS_FAILED = "failed";
}
