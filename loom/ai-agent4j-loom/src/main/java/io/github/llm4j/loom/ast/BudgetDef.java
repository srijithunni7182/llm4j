package io.github.llm4j.loom.ast;

import java.math.BigDecimal;

/**
 * A budget written in a script: the run's {@code budget { }} block, an agent's {@code budget { }}
 * block, or a {@code budget N tokens} modifier on a step, loop or for-each. Unset fields are unlimited.
 */
public class BudgetDef {

    /** What happens when the budget runs out: stop (the default), pause until it refills, or ask a person. */
    public enum WhenExhausted { STOP, SUSPEND, ASK }

    private Long tokens;
    private Long calls;
    private BigDecimal cost;
    private Integer perCall;
    private Double warnAt;
    private io.github.llm4j.budget.Window window;
    private WhenExhausted whenExhausted;

    public Long getTokens() { return tokens; }
    public void setTokens(Long tokens) { this.tokens = tokens; }
    public Long getCalls() { return calls; }
    public void setCalls(Long calls) { this.calls = calls; }
    public BigDecimal getCost() { return cost; }
    public void setCost(BigDecimal cost) { this.cost = cost; }
    /** Output-token cap for each LLM answer (agent budgets only). */
    public Integer getPerCall() { return perCall; }
    public void setPerCall(Integer perCall) { this.perCall = perCall; }
    /** Warning threshold as a fraction (0–1]; null means the default. */
    public Double getWarnAt() { return warnAt; }
    public void setWarnAt(Double warnAt) { this.warnAt = warnAt; }

    /** The refill window ({@code tokens: 100000 per day}), or null for a lifetime budget. */
    public io.github.llm4j.budget.Window getWindow() { return window; }
    public void setWindow(io.github.llm4j.budget.Window window) { this.window = window; }
    /** Null means {@link WhenExhausted#STOP}. */
    public WhenExhausted getWhenExhausted() { return whenExhausted; }
    public void setWhenExhausted(WhenExhausted whenExhausted) { this.whenExhausted = whenExhausted; }

    /** True if this limits tokens, calls or cost (a {@code per_call} cap alone limits nothing). */
    public boolean hasLimits() {
        return tokens != null || calls != null || cost != null;
    }
}
