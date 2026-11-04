package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code extract Invoice from text -> invoice} or {@code extract Claim from (email = msg, order =
 * record) -> claim}: runs a declared {@link ExtractorDef} on the named inputs and binds the fields
 * to a record (docs/specs/24-stateless-nodes, R2).
 */
public final class ExtractStmt extends LocatedStatement {

    private final String extractor;
    private final List<RunStmt.Arg> inputs = new ArrayList<>();
    private final String variableName;
    private int retryCount;
    private long backoffMillis;
    private long timeoutMillis;
    private BudgetDef budget;
    private final List<Statement> onFailure = new ArrayList<>();

    /** Creates a statement that runs {@code extractor} and stores the fields in {@code variable}. */
    public ExtractStmt(String extractor, String variableName) {
        this.extractor = extractor;
        this.variableName = variableName;
    }

    /** The name of the extractor to run. */
    public String getExtractor() {
        return extractor;
    }

    /** The inputs: one unnamed value (name {@code ""}), or one entry per named input. */
    public List<RunStmt.Arg> getInputs() {
        return inputs;
    }

    /** The variable that receives the extracted fields. */
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

    /** A budget for this step alone, or null. */
    public BudgetDef getBudget() {
        return budget;
    }

    public void setBudget(BudgetDef budget) {
        this.budget = budget;
    }

    /** The statements that run when the step fails. */
    public List<Statement> getOnFailure() {
        return onFailure;
    }
}
