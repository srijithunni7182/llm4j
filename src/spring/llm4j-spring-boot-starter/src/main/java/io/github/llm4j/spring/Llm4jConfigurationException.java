package io.github.llm4j.spring;

/** A setting that cannot work, with what to do about it. Start-up fails with this; {@link Llm4jFailureAnalyzer} prints it as Description and Action. */
public class Llm4jConfigurationException extends RuntimeException {

    private final String action;

    public Llm4jConfigurationException(String message, String action) {
        super(message);
        this.action = action;
    }

    public String action() {
        return action;
    }
}
