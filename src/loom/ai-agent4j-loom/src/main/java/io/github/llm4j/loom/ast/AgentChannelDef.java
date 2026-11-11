package io.github.llm4j.loom.ast;

/**
 * {@code agent_channel Name { audience: staff max_questions: 3 timeout: 1h }}: who answers what an
 * agent from outside asks, in terms the audit can read. The implementation is registered by the host
 * under the same name. Which keys and values are allowed is checked at load time.
 */
public final class AgentChannelDef extends NamedSettings {

    /** The default number of questions one run and step may use. */
    public static final int DEFAULT_MAX_QUESTIONS = 3;

    /** The default time one exchange may take. */
    public static final String DEFAULT_TIMEOUT = "1h";

    public AgentChannelDef(String name) {
        super(name);
    }
}
