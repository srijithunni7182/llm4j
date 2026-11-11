package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code ask Channel "text" -> reply [expecting { … }]}: sends a question to a declared {@code
 * agent_channel} and binds the answer. Without {@code expecting} the answer is text; with it, a
 * typed object checked against the shape. It takes the usual {@code retry}, {@code backoff}, {@code
 * timeout} and {@code on_failure}.
 */
public final class AskStmt extends LocatedStatement {

    private final String channel;
    private final String text;
    private final String variable;
    private SchemaDef expecting;
    private int retryCount;
    private long backoffMillis;
    private long timeoutMillis;
    private final List<Statement> onFailure = new ArrayList<>();

    public AskStmt(String channel, String text, String variable) {
        this.channel = channel;
        this.text = text;
        this.variable = variable;
    }

    /** The name of the declared channel. */
    public String getChannel() {
        return channel;
    }

    /** The question; {name} placeholders are filled from the run's variables. */
    public String getText() {
        return text;
    }

    /** The variable that receives the answer. */
    public String getVariable() {
        return variable;
    }

    /** The shape the answer must have, or null for text. */
    public SchemaDef getExpecting() {
        return expecting;
    }

    public void setExpecting(SchemaDef expecting) {
        this.expecting = expecting;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public long getBackoffMillis() {
        return backoffMillis;
    }

    public void setBackoffMillis(long backoffMillis) {
        this.backoffMillis = backoffMillis;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public List<Statement> getOnFailure() {
        return onFailure;
    }
}
