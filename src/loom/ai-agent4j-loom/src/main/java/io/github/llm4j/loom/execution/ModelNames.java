package io.github.llm4j.loom.execution;

/**
 * An observing replay routes each agent to its own stand-in by naming its model {@code
 * observe:Agent:realModel}; what an agent is asked with is judged by the real model name.
 */
public final class ModelNames {

    private ModelNames() {}

    public static final String OBSERVE = "observe:";

    /** The model an agent really uses, given the name it may carry in an observing replay. */
    public static String real(String model) {
        if (model == null || !model.startsWith(OBSERVE)) return model;
        int second = model.indexOf(':', OBSERVE.length());
        return second < 0 ? "" : model.substring(second + 1);
    }

    /** The agent or classifier an observing model name stands for. */
    public static String agentOf(String model) {
        if (model == null || !model.startsWith(OBSERVE)) return model;
        int second = model.indexOf(':', OBSERVE.length());
        return second < 0
                ? model.substring(OBSERVE.length())
                : model.substring(OBSERVE.length(), second);
    }
}
