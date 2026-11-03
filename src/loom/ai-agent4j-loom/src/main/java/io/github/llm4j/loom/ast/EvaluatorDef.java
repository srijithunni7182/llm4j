package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code evaluator Compliance { model: "…" unsure below 0.8 samples: 3 }}: a stateless model step
 * that judges a text against the rules of a policy and nothing else (docs/specs/26-policy-boundary,
 * R2). Like an extractor it has no tools, no memory, no persona and no loop, and reads neither the
 * run transcript nor the shared prompt.
 */
public final class EvaluatorDef implements Node {

    /** The confidence below which a rule's answer counts as unsure unless the script says so. */
    public static final double DEFAULT_UNSURE_BELOW = 0.8;

    private final String name;
    private int line;
    private String model;
    private double unsureBelow = DEFAULT_UNSURE_BELOW;
    private boolean unsureBelowWritten;
    private int samples = 1;
    private String pii;
    private final List<String> fallback = new ArrayList<>();
    private long timeoutMillis;
    private BudgetDef budget;

    /** Creates an evaluator called {@code name}. */
    public EvaluatorDef(String name) {
        this.name = name;
    }

    /** The name used after {@code using} and {@code checked by}. */
    public String getName() {
        return name;
    }

    /** The 1-based line of the declaration, or 0. */
    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    /** The model that judges. */
    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    /** The confidence below which a rule's answer is "unsure". */
    public double getUnsureBelow() {
        return unsureBelow;
    }

    public void setUnsureBelow(double unsureBelow) {
        this.unsureBelow = unsureBelow;
        this.unsureBelowWritten = true;
    }

    /** True when the script wrote {@code unsure below}, so a check can say it needs samples. */
    public boolean isUnsureBelowWritten() {
        return unsureBelowWritten;
    }

    /** How many times the model is asked; the majority answers and agreement is the confidence. */
    public int getSamples() {
        return samples;
    }

    public void setSamples(int samples) {
        this.samples = samples;
    }

    /** {@code mask}, {@code block}, {@code warn}, or null. */
    public String getPii() {
        return pii;
    }

    public void setPii(String pii) {
        this.pii = pii;
    }

    /** Models used, in order, when the first cannot answer. */
    public List<String> getFallback() {
        return fallback;
    }

    /** A per-call timeout in milliseconds, 0 for none. */
    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /** The evaluator's own budget, or null. */
    public BudgetDef getBudget() {
        return budget;
    }

    public void setBudget(BudgetDef budget) {
        this.budget = budget;
    }
}
