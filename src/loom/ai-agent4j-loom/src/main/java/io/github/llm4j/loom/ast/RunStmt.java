package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code run RefundPolicy(order = request.order_id, amount = 40) -> verdict}: runs a deterministic {@code Task} (plain Java, no model)
 * and binds its result map ({@code outcome}, {@code reason}, {@code value}, data) to {@code verdict}.
 */
public class RunStmt implements Statement {

    /** How an argument was written. */
    public enum Kind {
        /** A variable or dotted path: the variable's typed value. */
        REFERENCE,
        /** A quoted string: text, with {@code {placeholders}} filled in. */
        STRING,
        /** A number written as a literal. */
        NUMBER,
        /** {@code true} or {@code false}. */
        BOOLEAN
    }

    /** One {@code name = value} argument; {@code text} is the value as written (without quotes). */
    public record Arg(String name, Kind kind, String text) { }

    private final String taskName;
    private final List<Arg> args = new ArrayList<>();
    private final String variableName;
    private int retryCount;
    /** Wait before each retry, doubling every attempt; 0 = retry at once. */
    private long backoffMillis;
    /** Give up an attempt after this long; 0 = no limit. */
    private long timeoutMillis;
    private final List<Statement> onFailure = new ArrayList<>();
    private int line;

    public RunStmt(String taskName, String variableName) {
        this.taskName = taskName;
        this.variableName = variableName;
    }

    public String getTaskName() { return taskName; }
    public List<Arg> getArgs() { return args; }
    public String getVariableName() { return variableName; }
    public boolean hasDynamicVariable() { return variableName.startsWith("{"); }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public long getBackoffMillis() { return backoffMillis; }
    public void setBackoffMillis(long backoffMillis) { this.backoffMillis = backoffMillis; }
    public long getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(long timeoutMillis) { this.timeoutMillis = timeoutMillis; }
    public List<Statement> getOnFailure() { return onFailure; }
    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }
}
