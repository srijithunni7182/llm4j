package io.github.llm4j.loom.runtime;

import io.github.llm4j.agent.AgentInterrupt;
import io.github.llm4j.ratelimit.RateLimitInfo;
import java.time.Instant;

/**
 * Thrown out of {@code executeWorkflow} when a run pauses: the run holds no thread while it waits.
 * <ul>
 *   <li>{@link Reason#HUMAN}: record the answer in the {@link RunJournal} under {@link #stepId()} and run
 *       the workflow again with the same journal.</li>
 *   <li>{@link Reason#RATE_LIMIT} / {@link Reason#BUDGET_WINDOW}: run it again at {@link #resumeAt()}
 *       (a trigger store does this for you). Everything done so far replays; the step that hit the
 *       limit runs again.</li>
 * </ul>
 */
public class RunSuspended extends AgentInterrupt {

    public enum Reason { HUMAN, RATE_LIMIT, BUDGET_WINDOW }

    private final String stepId;
    private final String prompt;
    private final Reason reason;
    private final Instant resumeAt;
    private final RateLimitInfo limit;

    public RunSuspended(String stepId, String prompt) {
        super("Waiting for a human at " + stepId);
        this.stepId = stepId;
        this.prompt = prompt;
        this.reason = Reason.HUMAN;
        this.resumeAt = null;
        this.limit = null;
    }

    public RunSuspended(String stepId, Reason reason, RateLimitInfo limit) {
        super("Paused at " + stepId + ": " + limit.describe() + "; resumes at " + limit.resetAt());
        this.stepId = stepId;
        this.prompt = null;
        this.reason = reason;
        this.resumeAt = limit.resetAt();
        this.limit = limit;
    }

    public String stepId() {
        return stepId;
    }

    /** The question for a human, or null for a limit. */
    public String prompt() {
        return prompt;
    }

    public Reason reason() {
        return reason;
    }

    /** When the run can continue, or null when it waits for a human. */
    public Instant resumeAt() {
        return resumeAt;
    }

    /** The limit that paused the run, or null. */
    public RateLimitInfo limit() {
        return limit;
    }
}
