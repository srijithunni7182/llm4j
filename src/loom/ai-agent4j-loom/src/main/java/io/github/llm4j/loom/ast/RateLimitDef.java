package io.github.llm4j.loom.ast;

import java.time.Duration;

/**
 * What a run does when a provider rate limit (or quota) stops it:
 * {@code rate_limits { on_limit: suspend  max_wait: 24h  max_resumes: 50 }}. Unset fields take the
 * executor's defaults.
 */
public class RateLimitDef {

    public enum OnLimit { SUSPEND, WAIT, FAIL }

    private OnLimit onLimit;
    private Duration maxWait;
    private Integer maxResumes;

    public OnLimit getOnLimit() { return onLimit; }
    public void setOnLimit(OnLimit onLimit) { this.onLimit = onLimit; }
    /** The longest wait accepted (inline for wait, until resume for suspend); longer ones fail the run. */
    public Duration getMaxWait() { return maxWait; }
    public void setMaxWait(Duration maxWait) { this.maxWait = maxWait; }
    /** How many times one run may be resumed after limits before it is failed. */
    public Integer getMaxResumes() { return maxResumes; }
    public void setMaxResumes(Integer maxResumes) { this.maxResumes = maxResumes; }
}
