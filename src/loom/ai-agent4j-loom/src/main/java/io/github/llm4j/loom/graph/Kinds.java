package io.github.llm4j.loom.graph;

import java.util.List;

/** The node kinds a graph can contain. These strings are part of the JSON and the report schema. */
public final class Kinds {

    public static final String START = "start";
    public static final String END = "end";
    public static final String DELEGATE = "delegate";
    /** A {@code run} statement: a deterministic task, plain code with no model. */
    public static final String TASK = "task";
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
    public static final String UNKNOWN = "unknown";

    public static final List<String> ALL = List.of(
            START, END, DELEGATE, TASK, HANDOFF, BROADCAST, PARALLEL, ALT, LOOP, FOREACH, HUMAN_PROMPT,
            CHECKPOINT, REWIND, CALL, GUARDRAIL, DECIDE, OBSERVE, NOTE, UNKNOWN);

    private Kinds() {
    }
}
