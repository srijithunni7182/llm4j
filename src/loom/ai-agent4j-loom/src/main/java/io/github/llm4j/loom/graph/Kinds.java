package io.github.llm4j.loom.graph;

import java.util.List;

/** The node kinds a graph can contain. These strings are part of the JSON and the report schema. */
public final class Kinds {

    public static final String START = "start";
    public static final String END = "end";
    public static final String DELEGATE = "delegate";

    /** A {@code run} statement: a deterministic task, plain code with no model. */
    public static final String TASK = "task";

    /**
     * An {@code intentclassify} statement: a typed question put to a classifier backend (a chat
     * model or Jev), answered in one call.
     */
    public static final String CLASSIFY = "classify";

    /**
     * An {@code extract} statement: a stateless model step that pulls typed fields out of the text
     * it is handed and nothing else.
     */
    public static final String EXTRACT = "extract";

    /**
     * A {@code check … against} statement: a stateless evaluator judges a text against a policy's
     * rules before it can leave the workflow.
     */
    public static final String CHECK = "check";

    /**
     * A {@code use Tool(...)} statement: a declared tool called with named arguments and no model
     * in between.
     */
    public static final String USE = "use";

    /**
     * A {@code delegate} to a {@code remote} agent that someone else runs, reached over A2A. It is
     * drawn apart from a local {@code delegate} because it differs in the ways that matter: it is
     * opaque, its reply is untrusted, its cost is outside the budgets and it can take hours.
     */
    public static final String REMOTE = "remote";

    /** An {@code ask} statement: a question put to a declared {@code agent_channel}. */
    public static final String ASK = "ask";

    public static final String HANDOFF = "handoff";
    public static final String BROADCAST = "broadcast";
    public static final String PARALLEL = "parallel";
    public static final String ALT = "alt";
    public static final String LOOP = "loop";
    public static final String FOREACH = "foreach";
    public static final String HUMAN_PROMPT = "human_prompt";
    public static final String CHECKPOINT = "checkpoint";
    public static final String REWIND = "rewind";
    public static final String CALL = "call";
    public static final String GUARDRAIL = "guardrail";
    public static final String DECIDE = "decide";
    public static final String OBSERVE = "observe";
    public static final String NOTE = "note";

    /** A {@code halt} statement: the run ends here on purpose. */
    public static final String HALT = "halt";

    /** A {@code set} statement: a variable is given a value. */
    public static final String SET = "set";

    /** A {@code return} statement: the workflow hands back its {@code result} and ends. */
    public static final String RETURN = "return";

    /** A {@code sleep} statement: the workflow waits for a fixed time. */
    public static final String SLEEP = "sleep";

    /**
     * What an {@code on_stopped} handler decides for a stopped step: {@code use partial}, {@code
     * skip}, {@code retry} and so on.
     */
    public static final String OUTCOME = "outcome";

    /** A {@code start} statement: a workflow started as a child run of its own, a handle bound. */
    public static final String CHILD_START = "child_start";

    /** A {@code wait} statement: parks until a child, or every child of a keyed variable, settles. */
    public static final String CHILD_WAIT = "child_wait";

    /** A {@code cancel} statement: stops a started workflow. */
    public static final String CHILD_CANCEL = "child_cancel";

    /** A {@code steer} statement or a {@code supervise} block on a handle. */
    public static final String CHILD_WATCH = "child_watch";

    /** A {@code wait any} statement: returns on the first signal, finish or timeout of several children (phase 34d). */
    public static final String CHILD_WAIT_ANY = "child_wait_any";

    /** A {@code notify} statement: a signal to a child or to the parent (phase 34c). */
    public static final String CHILD_NOTIFY = "child_notify";

    /** A {@code wait signal} statement: waits for a signal from the parent or from a child (phase 34c). */
    public static final String CHILD_SIGNAL_WAIT = "child_signal_wait";

    /** A {@code report} statement: a workflow says where it is, for whoever started it (phase 34b). */
    public static final String CHILD_REPORT = "child_report";

    public static final String UNKNOWN = "unknown";

    public static final List<String> ALL =
            List.of(
                    START,
                    END,
                    DELEGATE,
                    TASK,
                    CLASSIFY,
                    EXTRACT,
                    CHECK,
                    USE,
                    HANDOFF,
                    BROADCAST,
                    PARALLEL,
                    ALT,
                    LOOP,
                    FOREACH,
                    HUMAN_PROMPT,
                    CHECKPOINT,
                    REWIND,
                    CALL,
                    GUARDRAIL,
                    DECIDE,
                    OBSERVE,
                    NOTE,
                    HALT,
                    SET,
                    RETURN,
                    SLEEP,
                    CHILD_START,
                    CHILD_WAIT,
                    CHILD_CANCEL,
                    CHILD_WATCH,
                    CHILD_REPORT,
                    CHILD_WAIT_ANY,
                    CHILD_NOTIFY,
                    CHILD_SIGNAL_WAIT,
                    OUTCOME,
                    UNKNOWN);

    private Kinds() {}
}
