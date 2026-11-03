package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code check reply against HouseRules using Compliance -> verdict on_violation { rewrite with
 * Editor }}: judges a text against the rules of one or more policies and takes one action when it
 * breaks them (docs/specs/26-policy-boundary, R4, R5). The statement is where a model-written value
 * is cleared to leave the workflow.
 */
public final class CheckStmt extends LocatedStatement {

    /** What happens when the text breaks a rule, or when the evaluator could not answer. */
    public sealed interface Action permits Rewrite, AskPerson, Replace, Fail {}

    /**
     * The named agent writes the text again from the text and the rules broken only; the new text
     * is checked once more.
     */
    public record Rewrite(String agent) implements Action {}

    /** A person sees the text and the rules broken, and sends it, replaces it or stops. */
    public record AskPerson() implements Action {}

    /** The subject is replaced by a fixed text. */
    public record Replace(String text) implements Action {}

    /** The step fails. This is the default. */
    public record Fail() implements Action {}

    private final String subject;
    private final List<String> policies = new ArrayList<>();
    private final String evaluator;
    private String verdictVariable;
    private int retryCount;
    private long timeoutMillis;
    private Action onViolation = new Fail();
    private Action onUnavailable = new Fail();
    private final List<Statement> onFailure = new ArrayList<>();

    /** Creates a check of {@code subject} using {@code evaluator}. */
    public CheckStmt(String subject, String evaluator) {
        this.subject = subject;
        this.evaluator = evaluator;
    }

    /** The variable or field path holding the text. */
    public String getSubject() {
        return subject;
    }

    /** The policies the text is checked against, in order. */
    public List<String> getPolicies() {
        return policies;
    }

    /** The evaluator that judges. */
    public String getEvaluator() {
        return evaluator;
    }

    /** The variable that receives the verdict, or null. */
    public String getVerdictVariable() {
        return verdictVariable;
    }

    public void setVerdictVariable(String verdictVariable) {
        this.verdictVariable = verdictVariable;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /** What happens on a violation; {@link Fail} when the script wrote nothing. */
    public Action getOnViolation() {
        return onViolation;
    }

    public void setOnViolation(Action onViolation) {
        this.onViolation = onViolation;
    }

    /** What happens when the evaluator could not answer; {@link Fail} unless written. */
    public Action getOnUnavailable() {
        return onUnavailable;
    }

    public void setOnUnavailable(Action onUnavailable) {
        this.onUnavailable = onUnavailable;
    }

    /** The statements that run when the step ends in failure. */
    public List<Statement> getOnFailure() {
        return onFailure;
    }
}
