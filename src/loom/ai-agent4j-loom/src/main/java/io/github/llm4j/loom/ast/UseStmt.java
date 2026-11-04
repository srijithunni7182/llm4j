package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code use Notify(text = draft) approve -> receipt retry 1 timeout 30s on_declined { … }
 * on_failure { … }}: calls a declared tool with named arguments and no model in between
 * (docs/specs/27-boundary-approvals, R1, R2). It goes through the same tool object, reach checks,
 * secrets, journal and effect protocol as an agent's call to the same tool.
 */
public final class UseStmt extends LocatedStatement {

    private final String tool;
    private final List<RunStmt.Arg> args = new ArrayList<>();
    private final String variableName;
    private boolean approve;
    private String approveHeading;
    private int retryCount;
    private long backoffMillis;
    private long timeoutMillis;
    private final List<Statement> onDeclined = new ArrayList<>();
    private final List<Statement> onFailure = new ArrayList<>();

    /** Creates a call of {@code tool} whose result goes to {@code variableName} (or null). */
    public UseStmt(String tool, String variableName) {
        this.tool = tool;
        this.variableName = variableName;
    }

    /** The tool's name, as declared or built in. */
    public String getTool() {
        return tool;
    }

    /** The named arguments, in the order written. */
    public List<RunStmt.Arg> getArgs() {
        return args;
    }

    /** The variable that receives the result, or null when the result is not kept. */
    public String getVariableName() {
        return variableName;
    }

    /** True when a person is asked before the call runs. */
    public boolean isApprove() {
        return approve;
    }

    public void setApprove(boolean approve) {
        this.approve = approve;
    }

    /** The heading of the question put to the person, or null. */
    public String getApproveHeading() {
        return approveHeading;
    }

    public void setApproveHeading(String approveHeading) {
        this.approveHeading = approveHeading;
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

    /** What runs when the person says no. Allowed only with {@link #isApprove()}. */
    public List<Statement> getOnDeclined() {
        return onDeclined;
    }

    /** What runs when the call fails. */
    public List<Statement> getOnFailure() {
        return onFailure;
    }
}
