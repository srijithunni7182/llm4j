package io.github.llm4j.loom.runtime;

import io.github.llm4j.agent.AgentInterrupt;
import io.github.llm4j.ratelimit.RateLimitInfo;
import java.time.Instant;

/**
 * Thrown out of {@code executeWorkflow} when a run pauses: the run holds no thread while it waits.
 *
 * <ul>
 *   <li>{@link Reason#HUMAN}: record the answer in the {@link RunJournal} under {@link #stepId()}
 *       and run the workflow again with the same journal.
 *   <li>{@link Reason#RATE_LIMIT} / {@link Reason#BUDGET_WINDOW}: run it again at {@link
 *       #resumeAt()} (a trigger store does this for you). Everything done so far replays; the step
 *       that hit the limit runs again.
 *   <li>{@link Reason#SLEEP}: a {@code sleep} step; run it again at {@link #resumeAt()}. The wake
 *       time is journaled, so a restart never lengthens the sleep, and a sleep is not counted
 *       against {@code max_resumes}.
 *   <li>{@link Reason#REMOTE}: a remote agent's task is not finished; run it again when the task
 *       changes, or at {@link #resumeAt()}, the step's deadline.
 *   <li>{@link Reason#OPERATOR}: a person asked for the run to be suspended (from an observer's
 *       control). It holds nothing and schedules nothing: run it again with the same journal when
 *       you want it to go on.
 * </ul>
 */
public class RunSuspended extends AgentInterrupt {

    public enum Reason {
        HUMAN,
        RATE_LIMIT,
        BUDGET_WINDOW,
        SLEEP,
        /**
         * A remote agent's task is not finished. The run holds no thread: the host calls {@code
         * taskChanged} when it learns the task changed (polling, a stream or a push notification),
         * which runs the workflow again with the same journal, or it runs it again at {@link
         * #resumeAt()}, the step's deadline, when the step fails.
         */
        REMOTE,
        OPERATOR
    }

    private final String stepId;
    private final String prompt;
    private final Reason reason;
    private final Instant resumeAt;
    private final RateLimitInfo limit;
    private String detail;

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

    /** The run was suspended at {@code stepId} by {@code who}, to be resumed by hand. */
    public static RunSuspended byOperator(String stepId, String who) {
        return new RunSuspended(stepId, "suspended by " + who, Reason.OPERATOR);
    }

    private RunSuspended(String stepId, String prompt, Reason reason) {
        super("Suspended at " + stepId + " (" + prompt + ")");
        this.stepId = stepId;
        this.prompt = prompt;
        this.reason = reason;
        this.resumeAt = null;
        this.limit = null;
    }

    /**
     * A step waiting on a remote agent's task.
     *
     * @param stepId the step
     * @param deadline when the step gives up if the task has not changed
     * @param detail what the run waits for, in words
     */
    public static RunSuspended remote(String stepId, Instant deadline, String detail) {
        return new RunSuspended(stepId, deadline, detail, Reason.REMOTE);
    }

    private RunSuspended(String stepId, Instant resumeAt, String detail, Reason reason) {
        super("Waiting at " + stepId + ": " + detail + "; gives up at " + resumeAt);
        this.stepId = stepId;
        this.prompt = null;
        this.reason = reason;
        this.resumeAt = resumeAt;
        this.limit = null;
        this.detail = detail;
    }

    /** A {@code sleep} step that runs until {@code wakeAt}. */
    public RunSuspended(String stepId, Instant wakeAt, String detail) {
        super("Sleeping at " + stepId + ": " + detail + "; wakes at " + wakeAt);
        this.stepId = stepId;
        this.prompt = null;
        this.reason = Reason.SLEEP;
        this.resumeAt = wakeAt;
        this.limit = null;
        this.detail = detail;
    }

    /**
     * What the run is waiting for, in words ({@code sleeping 90s}), or null when there is a {@link
     * #limit()} to describe instead.
     */
    public String detail() {
        return detail;
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
