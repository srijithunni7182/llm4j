package io.github.llm4j.tools;
import io.github.llm4j.agent.tool.EffectJournal;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.Effectful;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.agent.tool.Outcome;

import io.github.llm4j.agent.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps a side-effect tool from acting twice when a run is resumed. Each effect call is written to the run
 * journal as {@code pending} before it happens and as {@code done} after; run again on the same journal,
 * a {@code done} call returns what it returned, and a {@code pending} one (the process died in between) is
 * handled by the tool's {@link EffectPolicy}.
 *
 * <p>Calls the tool says are not effects (reads) pass straight through.
 */
public final class EffectTool implements Tool {

    static final String PENDING = "effect_pending";
    static final String DONE = "effect_done";
    static final String FAILED = "effect_failed";
    static final String REPLAYED = "(already done in an earlier attempt) ";
    private static final String RUN_UID_KEY = "#effect-run-uid";

    private final Effectful delegate;
    private final EffectContext context;
    private final ThreadLocal<Attempt> attempt = new ThreadLocal<>();

    public EffectTool(Effectful delegate, EffectContext context) {
        this.delegate = delegate;
        this.context = context;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public boolean requiresApproval(Map<String, Object> args) {
        return delegate.requiresApproval(args);
    }

    @Override
    public String execute(Map<String, Object> rawArgs) {
        Map<String, Object> args = rawArgs == null ? Map.of() : rawArgs;
        if (!delegate.isEffect(args)) return delegate.execute(args);

        EffectJournal journal = context.journal();
        String tool = delegate.getName();
        String hash = CanonicalArgs.hash12(tool, args);
        String callId = tool + hash;
        String key = context.currentStep() + "#effect:" + tool + ":" + hash + "#" + nextOrdinal(callId);
        EffectJournal.Entry earlier = journal.get(key).orElse(null);
        EffectPolicy policy = delegate.policy();
        long started = context.clock().millis();

        if (earlier != null && DONE.equals(earlier.kind())) {
            record("replayed", args, started);
            return REPLAYED + earlier.value();
        }
        if (earlier != null && PENDING.equals(earlier.kind())) {
            audit("effect_unknown", args, policy.idempotent() ? "retry-idempotent" : policy.onUnknown().name().toLowerCase());
            if (!policy.idempotent() && policy.onUnknown() == EffectPolicy.OnUnknown.SKIP) {
                record("unknown-skipped", args, started);
                return "Error: an earlier attempt's outcome is unknown, so it was not repeated. "
                        + "Check whether it happened before trying again.";
            }
        }
        // Check the allowance and claim the call together, so parallel branches can't all slip under the limit.
        synchronized (journal) {
            if (policy.maxPerRun() > 0 && earlier == null && callsSoFar(journal, tool) >= policy.maxPerRun()) {
                return "Error: " + tool + " is limited to " + policy.maxPerRun() + " calls per run, and has used them.";
            }
            journal.put(key, new EffectJournal.Entry(PENDING, ""));
        }
        Outcome outcome = delegate.perform(args, policy.idempotent() ? idempotencyKey(journal, key) : null);
        switch (outcome.status()) {
            case OK -> journal.put(key, new EffectJournal.Entry(DONE, outcome.text()));
            case FAILED -> {
                journal.put(key, new EffectJournal.Entry(FAILED, outcome.text()));
                giveBackOrdinal(callId); // the retry of a failed call is the same call
            }
            case UNKNOWN -> { /* stays pending: the next attempt applies the policy */ }
        }
        record(outcome.status().name().toLowerCase(), args, started);
        return outcome.text();
    }

    /** Counts calls with identical arguments within this attempt of this step: the first is 0. */
    private int nextOrdinal(String callId) {
        long current = context.attempt();
        String step = context.currentStep();
        Attempt a = attempt.get();
        if (a == null || a.attempt != current || !a.step.equals(step)) {
            a = new Attempt(step, current);
            attempt.set(a);
        }
        return a.counts.merge(callId, 1, Integer::sum) - 1;
    }

    private void giveBackOrdinal(String callId) {
        Attempt a = attempt.get();
        if (a != null) a.counts.merge(callId, -1, Integer::sum);
    }

    /** Effect records for this tool that happened or may have: failed attempts don't use up the allowance. */
    private static long callsSoFar(EffectJournal journal, String tool) {
        String marker = "#effect:" + tool + ":";
        return journal.all().entrySet().stream()
                .filter(e -> e.getKey().contains(marker))
                .filter(e -> PENDING.equals(e.getValue().kind()) || DONE.equals(e.getValue().kind()))
                .count();
    }

    /** Stable across a resume and unique to this journal, so a daily run doesn't reuse yesterday's keys. */
    private static String idempotencyKey(EffectJournal journal, String effectKey) {
        String uid = journal.get(RUN_UID_KEY).map(e -> String.valueOf(e.value())).orElseGet(() -> {
            String fresh = java.util.UUID.randomUUID().toString();
            journal.put(RUN_UID_KEY, new EffectJournal.Entry("effect_run", fresh));
            return fresh;
        });
        return CanonicalArgs.sha256Hex(uid + effectKey).substring(0, 32);
    }

    private void record(String outcome, Map<String, Object> args, long started) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", delegate.getName());
        data.put("step", context.currentStep());
        data.put("target", target(args));
        data.put("outcome", outcome);
        data.put("millis", context.clock().millis() - started);
        context.audit("tool_effect", data);
        context.trace("tool effect: " + delegate.getName() + " " + data.get("target") + " → " + outcome, data);
    }

    private void audit(String event, Map<String, Object> args, String policy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", delegate.getName());
        data.put("step", context.currentStep());
        data.put("policy", policy);
        context.audit(event, data);
    }

    private String target(Map<String, Object> args) {
        try {
            return delegate.target(args);
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static final class Attempt {
        final String step;
        final long attempt;
        final Map<String, Integer> counts = new java.util.HashMap<>();

        Attempt(String step, long attempt) {
            this.step = step;
            this.attempt = attempt;
        }
    }
}
