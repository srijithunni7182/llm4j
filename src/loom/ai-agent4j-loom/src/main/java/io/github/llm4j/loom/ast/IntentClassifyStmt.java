package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code intentclassify Intent on message -> intent}: asks a declared {@link IntentClassifierDef}
 * about the state and binds the answers to a record: {@code intent.kind}, {@code intent.urgent},
 * {@code intent.confidence}, {@code intent.calibrated}, … The state is one value ({@code on
 * message}, {@code on "text with {vars}"}) or named fields ({@code on { message: message, history:
 * past }}).
 */
public class IntentClassifyStmt extends LocatedStatement {

    private final String classifier;
    private final List<RunStmt.Arg> inputs = new ArrayList<>();
    private final String variableName;
    private int retryCount;
    private long backoffMillis;
    private long timeoutMillis;
    private final List<Statement> onFailure = new ArrayList<>();

    public IntentClassifyStmt(String classifier, String variableName) {
        this.classifier = classifier;
        this.variableName = variableName;
    }

    public String getClassifier() {
        return classifier;
    }

    /** The state: one unnamed value (name {@code ""}), or one entry per named field. */
    public List<RunStmt.Arg> getInputs() {
        return inputs;
    }

    public String getVariableName() {
        return variableName;
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
