package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RateLimitDef;
import io.github.llm4j.loom.runtime.RateLimitFailure;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Logger;

/**
 * What the run does when a model call is limited (a provider's rate limit, or a budget that refills): wait it out in place, pause the run until the reset,
 * or fail, as the script's {@code rate_limits} block says. {@link HarnessExecutor} owns one.
 */
final class LimitPolicy {
    private static final Logger log = Logger.getLogger(HarnessExecutor.class.getName());

    static final int DEFAULT_MAX_RESUMES = 50;
    static final Duration DEFAULT_MAX_INLINE_WAIT = Duration.ofMinutes(5);
    static final Duration DEFAULT_MAX_SUSPEND = Duration.ofDays(7);

    /** What the policy needs from the run. */
    interface Host {
        LoomScript script();

        RunJournal journal();

        java.time.Clock clock();

        io.github.llm4j.ratelimit.Sleeper sleeper();

        /** The id of the step the calling thread is executing. */
        String stepId();

        io.github.llm4j.audit.AuditLogger auditLogger();

        String sessionId();

        /** True when the refusing budget refills at a known time and its script says to wait for that. */
        boolean refills(io.github.llm4j.budget.BudgetExceeded be);
    }

    private final Host host;

    LimitPolicy(Host host) {
        this.host = host;
    }

    RateLimitDef.OnLimit onLimitPolicy() {
        RateLimitDef rl = host.script().getRateLimits();
        if (rl != null && rl.getOnLimit() != null) return rl.getOnLimit();
        return host.journal().isDurable() ? RateLimitDef.OnLimit.SUSPEND : RateLimitDef.OnLimit.WAIT;
    }

    Duration maxWait(RateLimitDef.OnLimit policy) {
        RateLimitDef rl = host.script().getRateLimits();
        if (rl != null && rl.getMaxWait() != null) return rl.getMaxWait();
        return policy == RateLimitDef.OnLimit.WAIT ? DEFAULT_MAX_INLINE_WAIT : DEFAULT_MAX_SUSPEND;
    }

    int maxResumes() {
        RateLimitDef rl = host.script().getRateLimits();
        return rl != null && rl.getMaxResumes() != null ? rl.getMaxResumes() : DEFAULT_MAX_RESUMES;
    }

    /**
     * A step hit a limit. Returns after waiting it out inline (the caller runs the step again), or throws
     * {@link RunSuspended} to pause the run until the reset, or {@link RateLimitFailure}.
     * A budget that refills pauses the run (its {@code when_exhausted: suspend}); a provider limit
     * follows {@code rate_limits { on_limit }}.
     */
    void onLimit(io.github.llm4j.ratelimit.RateLimited limited, int[] waits) {
        io.github.llm4j.ratelimit.RateLimitInfo info = limited.info();
        boolean window = limited.reason() == io.github.llm4j.ratelimit.RateLimited.Reason.BUDGET_WINDOW;
        RateLimitDef.OnLimit policy = window ? RateLimitDef.OnLimit.SUSPEND : onLimitPolicy();
        Duration wait = info.waitFrom(host.clock().instant());
        Duration max = maxWait(policy);
        String stepId = host.stepId();
        if (policy == RateLimitDef.OnLimit.FAIL) throw new RateLimitFailure(info, null);
        if (wait.compareTo(max) > 0) {
            throw new RateLimitFailure(info, "that is more than max_wait " + human(max) + " away");
        }
        if (policy == RateLimitDef.OnLimit.SUSPEND) {
            throw new RunSuspended(stepId, window ? RunSuspended.Reason.BUDGET_WINDOW : RunSuspended.Reason.RATE_LIMIT, info);
        }
        if (++waits[0] > maxResumes()) {
            throw new RateLimitFailure(info, "still limited after " + (waits[0] - 1) + " waits (max_resumes)");
        }
        log.warning("Rate limited at " + stepId + " (" + info.describe() + "): waiting " + human(wait));
        Map<String, Object> data = limitData(info);
        data.put("step", stepId);
        data.put("waitMillis", String.valueOf(wait.toMillis()));
        host.auditLogger().logConversationEvent(host.sessionId(), null, "rate_limit_wait", data);
        try {
            host.sleeper().sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a rate limit", e);
        }
    }

    /** Runs an agent, waiting out or pausing for limits as {@link #onLimit} decides. */
    io.github.llm4j.agent.AgentResult runUnderLimits(ReActAgent agent, String task) {
        int[] waits = {0};
        while (true) {
            try {
                io.github.llm4j.agent.AgentResult r = agent.run(task);
                if (r.budgetExhausted() && host.refills(r.getBudgetExceeded())) {
                    throw io.github.llm4j.ratelimit.RateLimited.of(r.getBudgetExceeded());
                }
                return r;
            } catch (io.github.llm4j.ratelimit.RateLimited limited) {
                onLimit(limited, waits);
            }
        }
    }

    static String human(Duration d) {
        long s = d.toSeconds();
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m" + (s % 60 == 0 ? "" : (s % 60) + "s");
        if (s < 86_400) return (s / 3600) + "h" + ((s % 3600) / 60 == 0 ? "" : ((s % 3600) / 60) + "m");
        return (s / 86_400) + "d" + ((s % 86_400) / 3600 == 0 ? "" : ((s % 86_400) / 3600) + "h");
    }

    static Map<String, Object> limitData(io.github.llm4j.ratelimit.RateLimitInfo info) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("provider", info.provider());
        data.put("scope", info.scope().name());
        if (info.quotaId() != null) data.put("quotaId", info.quotaId());
        data.put("resumeAt", info.resetAt().toString());
        data.put("estimated", String.valueOf(info.estimated()));
        return data;
    }
}
