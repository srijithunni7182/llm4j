package io.github.llm4j.loom.inspect.state;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules that turn what a run did into incidents. Each is a small function of what has been seen so far, so a recorded stream of
 * events replays to the same incidents. Nothing here reads a prompt or an answer: it works from hashes and counts the process computed,
 * so it works at every detail level.
 *
 * <ul>
 *   <li><b>paused</b>: the run is paused by a limit (not a sleep, not a person). Information; a warning once it is longer than the threshold.
 *   <li><b>looping</b>: the same tool call {@code sameAction} times in one step (the rounds of a loop count together), or the same answer
 *       {@code sameOutput} times in a row from one step.
 *   <li><b>suspect output</b>: an answer states figures found in nothing the step was given or fetched. Says "no source found", never "false".
 *   <li><b>failed</b>: a tool fails {@code toolFailures} times in a row; ends at its next success.
 *   <li><b>budget</b>: the run's budget warned or ran out.
 * </ul>
 */
final class Detectors {

    private final Incidents incidents;
    private Thresholds t;
    private Map<String, InspectRules> rules = Map.of();
    private final Map<String, ArrayDeque<String>> progress = new HashMap<>();
    private final Map<String, Integer> actions = new HashMap<>();
    private final Map<String, ArrayDeque<String>> outputs = new HashMap<>();
    private final Map<String, Integer> failStreak = new HashMap<>();
    private final Map<String, Integer> truncations = new HashMap<>();
    private final Map<String, Long> modelCalls = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> compactionAt = new HashMap<>();
    private final Map<String, Integer> lowGain = new HashMap<>();
    private final Map<String, ArrayDeque<String>> toolWindow = new HashMap<>();
    private final Map<String, Integer> spikeStreak = new HashMap<>();
    private long pausedAt;
    private String pausedReason;
    private String pausedResumeAt;

    Detectors(Incidents incidents, Thresholds thresholds) {
        this.incidents = incidents;
        this.t = thresholds;
    }

    void thresholds(Thresholds thresholds) {
        this.t = thresholds;
    }

    /** The per-agent rules a script's {@code inspect} blocks give; an agent without one uses the defaults. */
    void rules(Map<String, InspectRules> rules) {
        this.rules = rules == null ? Map.of() : Map.copyOf(rules);
    }

    /** The defaults, with the block for every agent ({@code *}) laid over them, and then the agent's own block. */
    private Thresholds of(String agent) {
        InspectRules all = rules.get("*");
        Thresholds base = all == null ? t : all.over(t);
        InspectRules r = agent == null ? null : rules.get(agent);
        return r == null ? base : r.over(base);
    }

    /** The thresholds in force for an agent (or, for null, for every agent): the defaults, the `*` block, then the agent's own block. */
    Thresholds thresholdsFor(String agent) {
        return of(agent);
    }

    /** A pause belongs to the run, not to an agent: the shortest limit any agent set decides when it becomes a warning. */
    private long pausedLimit() {
        long limit = t.pausedLongerThanSeconds();
        for (InspectRules r : rules.values()) if (r.pausedLongerThanSeconds() != null) limit = Math.min(limit, r.pausedLongerThanSeconds());
        return limit;
    }

    // ------------------------------------------------------------------------------ looping

    void onToolStart(String agent, String step, String tool, String actionHash, long now) {
        if (agent == null || actionHash == null) return;
        String where = StepPaths.normalize(step);
        String key = agent + "|" + where + "|" + actionHash;
        Thresholds t = of(agent);
        int n = actions.merge(key, 1, Integer::sum);
        if (n >= t.sameAction()) {
            incidents.raise("loop:action:" + key, Incidents.Kind.LOOPING, "warn", agent, where, now,
                    agent + " keeps making the same call to " + tool,
                    List.of(Incidents.evidence("same_action", n, t.sameAction(), tool + " called " + n + " times with the same input in " + where)));
        }
    }

    void onAgentEnd(String agent, String step, String outputHash, long figures, long ungrounded, String examples, long now) {
        if (agent == null) return;
        String where = StepPaths.normalize(step);
        Thresholds t = of(agent);
        truncations.remove(agent + "|" + where);
        incidents.close("truncated:" + agent + "|" + where, now, "the step ended");
        if (outputHash != null) {
            ArrayDeque<String> last = outputs.computeIfAbsent(agent + "|" + where, k -> new ArrayDeque<>());
            if (!last.isEmpty() && !last.peekLast().equals(outputHash)) last.clear(); // a different answer: the streak is over
            last.addLast(outputHash);
            while (last.size() > 50) last.removeFirst();
            if (last.size() >= t.sameOutput()) {
                incidents.raise("loop:output:" + agent + "|" + where, Incidents.Kind.LOOPING, "warn", agent, where, now,
                        agent + " is giving the same answer again and again",
                        List.of(Incidents.evidence("same_output", last.size(), t.sameOutput(), "the same answer " + last.size() + " times in a row from " + where)));
            } else {
                incidents.close("loop:output:" + agent + "|" + where, now, "the answer changed");
            }
        }
        if (ungrounded >= t.ungroundedWarn()) {
            String severity = ungrounded >= t.ungroundedHigh() ? "high" : "warn";
            String detail = ungrounded + " of " + figures + " figures appear in nothing this step was given or fetched" + (examples == null || examples.isBlank() ? "" : " (" + examples + ")");
            incidents.raise("suspect:" + agent + "|" + where, Incidents.Kind.SUSPECT_OUTPUT, severity, agent, where, now,
                    "Suspect output from " + agent + ": figures with no source found",
                    List.of(Incidents.evidence("ungrounded_figures", ungrounded, severity.equals("high") ? t.ungroundedHigh() : t.ungroundedWarn(), detail)));
        }
    }

    // ------------------------------------------------------------------------------ no progress

    /**
     * The variable the rule names, as it stood at the boundary between two rounds of a loop (the value that round ended with). The same
     * value this many boundaries in a row is a loop that is not getting anywhere, which is what the script said progress would look like.
     */
    void onSample(String owner, String variable, String loop, String valueHash, long now) {
        InspectRules r = owner == null ? null : rules.get(owner);
        if (r == null || r.noProgressRounds() == null || valueHash == null) return;
        String key = "loop:progress:" + owner + "|" + variable + "|" + loop;
        ArrayDeque<String> last = progress.computeIfAbsent(key, k -> new ArrayDeque<>());
        if (!last.isEmpty() && !last.peekLast().equals(valueHash)) last.clear();
        last.addLast(valueHash);
        while (last.size() > 50) last.removeFirst();
        if (last.size() >= r.noProgressRounds()) {
            incidents.raise(key, Incidents.Kind.LOOPING, "warn", owner, loop, now,
                    variable + " has not changed for " + last.size() + " rounds",
                    List.of(Incidents.evidence("no_progress", last.size(), r.noProgressRounds(), variable + " was the same at the end of " + last.size() + " rounds in a row in " + loop)));
        } else {
            incidents.close(key, now, variable + " changed");
        }
    }

    // ------------------------------------------------------------------------------ failing tools

    void onToolEnd(String agent, String tool, boolean error, String step, long now) {
        if (tool == null) return;
        Thresholds t = of(agent);
        if (!error) {
            failStreak.remove(tool);
            incidents.close("failed:" + tool, now, "the tool answered again");
            return;
        }
        int n = failStreak.merge(tool, 1, Integer::sum);
        if (n >= t.toolFailures()) {
            incidents.raise("failed:" + tool, Incidents.Kind.FAILED, n >= 2 * t.toolFailures() ? "high" : "warn", agent, StepPaths.normalize(step), now,
                    "The tool " + tool + " keeps failing",
                    List.of(Incidents.evidence("tool_failures", n, t.toolFailures(), tool + " failed " + n + " times in a row")));
        }
    }


    // ------------------------------------------------------------------------------ context, truncation, compaction

    /** An agent's context use after a model call. Only judged when the model's window is known (the fraction is given). */
    void onContext(String agent, Double fraction, long used, Long usable, long compactions, long now) {
        if (agent == null || fraction == null) return;
        Thresholds t = of(agent);
        String key = "context:" + agent;
        long pct = Math.round(fraction * 100);
        String detail = used + " of " + (usable == null ? "?" : usable) + " tokens (" + pct + "%) after " + compactions + " compaction" + (compactions == 1 ? "" : "s") + " so far";
        if (fraction >= t.contextHigh()) {
            incidents.raise(key, Incidents.Kind.CONTEXT, "high", agent, null, now, agent + "'s context is almost full",
                    List.of(Incidents.evidence("context_nearly_full", fraction, t.contextHigh(), detail)));
        } else if (fraction >= t.contextWarn()) {
            incidents.raise(key, Incidents.Kind.CONTEXT, "warn", agent, null, now, agent + "'s context is nearly full",
                    List.of(Incidents.evidence("context_nearly_full", fraction, t.contextWarn(), detail)));
        } else {
            incidents.close(key, now, "the context is below " + Math.round(t.contextWarn() * 100) + "% again");
        }
    }

    /** A model call finished: counts it for the thrash window, and notes an answer cut off by the token limit. */
    void onModelCall(String agent, String step, boolean truncated, long completionTokens, String finishReason, long now) {
        if (agent == null) return;
        modelCalls.merge(agent, 1L, Long::sum);
        evaluateThrash(agent, now);
        if (!truncated) return;
        Thresholds t = of(agent);
        String where = StepPaths.normalize(step);
        String key = agent + "|" + where;
        int n = truncations.merge(key, 1, Integer::sum);
        if (n >= t.truncatedAnswers()) {
            incidents.raise("truncated:" + key, Incidents.Kind.TRUNCATED, n >= 3 ? "high" : "warn", agent, where, now,
                    agent + "'s answer was cut off by the token limit",
                    List.of(Incidents.evidence("truncated_answers", (long) n, t.truncatedAnswers(),
                            n + " model call" + (n == 1 ? "" : "s") + " in " + where + " ended at the token limit (" + completionTokens + " tokens"
                                    + (finishReason == null ? "" : ", finish reason " + finishReason) + ")")));
        }
    }

    /** A compaction was tried. */
    void onCompaction(String agent, String outcome, long before, long after, long now) {
        if (agent == null) return;
        long calls = modelCalls.getOrDefault(agent, 0L);
        if ("COMPACTED".equals(outcome)) compactionAt.computeIfAbsent(agent, k -> new ArrayDeque<>()).addLast(calls);
        boolean little = "NO_GAIN".equals(outcome) || "COMPACTED".equals(outcome) && before > 0 && (before - after) < before * 0.10;
        if ("COMPACTED".equals(outcome) || "NO_GAIN".equals(outcome)) {
            if (little) lowGain.merge(agent, 1, Integer::sum);
            else lowGain.remove(agent);
        }
        evaluateThrash(agent, now);
    }

    private static final int LOW_GAIN_RUN = 3;

    private void evaluateThrash(String agent, long now) {
        Thresholds t = of(agent);
        long calls = modelCalls.getOrDefault(agent, 0L);
        ArrayDeque<Long> at = compactionAt.get(agent);
        long within = 0;
        if (at != null) {
            while (!at.isEmpty() && at.peekFirst() <= calls - Math.max(t.thrashOfCalls(), 1) - 50) at.removeFirst(); // keep it bounded
            for (long idx : at) if (idx > calls - t.thrashOfCalls()) within++;
        }
        int streak = lowGain.getOrDefault(agent, 0);
        String key = "thrash:" + agent;
        if (within >= t.thrashCompactions()) {
            incidents.raise(key, Incidents.Kind.THRASH, "warn", agent, null, now, agent + " keeps compacting its context",
                    List.of(Incidents.evidence("compaction_thrash", within, t.thrashCompactions(), within + " compactions in the last " + t.thrashOfCalls() + " model calls")));
        } else if (streak >= LOW_GAIN_RUN) {
            incidents.raise(key, Incidents.Kind.THRASH, "warn", agent, null, now, agent + " compacts its context but gains little",
                    List.of(Incidents.evidence("compaction_thrash", (long) streak, LOW_GAIN_RUN, streak + " compactions in a row freed little or nothing")));
        } else {
            incidents.close(key, now, "compaction settled down");
        }
    }

    // ------------------------------------------------------------------------------ tool success rate and latency spikes

    /** A tool call that ran: its outcome, for the success rate, and its duration against what that tool usually takes. */
    void onToolCall(String agent, String tool, boolean ok, String category, long millis, long priorP50, long priorCount, long now) {
        if (tool == null) return;
        Thresholds t = of(agent);
        ArrayDeque<String> w = toolWindow.computeIfAbsent(tool, k -> new ArrayDeque<>());
        w.addLast(ok ? "" : category == null ? "other" : category);
        while (w.size() > Math.max(t.toolRateWindow(), Thresholds.MIN_RATE_CALLS)) w.removeFirst();
        String key = "toolrate:" + tool;
        if (w.size() >= Thresholds.MIN_RATE_CALLS) {
            long good = w.stream().filter(String::isEmpty).count();
            double rate = good / (double) w.size();
            if (rate < t.toolRate()) {
                Map<String, Integer> byCategory = new java.util.TreeMap<>();
                for (String c : w) if (!c.isEmpty()) byCategory.merge(c, 1, Integer::sum);
                String top = byCategory.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> e.getKey() + " (" + e.getValue() + ")").orElse("none");
                incidents.raise(key, Incidents.Kind.TOOL_RATE, rate < 0.4 ? "high" : "warn", agent, null, now, "The tool " + tool + " is failing often",
                        List.of(Incidents.evidence("tool_success_rate", rate, t.toolRate(), good + " of " + w.size() + " recent calls to " + tool + " succeeded; most common failure: " + top)));
            } else {
                incidents.close(key, now, tool + " is succeeding again");
            }
        }
        onDuration("tool", tool, agent, millis, priorP50, priorCount, now);
    }

    /** A model call's duration against what that model usually takes. */
    void onModelDuration(String model, String agent, long millis, long priorP50, long priorCount, long now) {
        onDuration("model", model, agent, millis, priorP50, priorCount, now);
    }

    private void onDuration(String kind, String name, String agent, long millis, long priorP50, long priorCount, long now) {
        Thresholds t = of(agent);
        String key = "spike:" + kind + ":" + name;
        boolean spike = priorCount >= Thresholds.MIN_SPIKE_SAMPLES && millis > Thresholds.SPIKE_FLOOR_MILLIS && millis > t.spikeFactor() * priorP50;
        if (spike) {
            int streak = spikeStreak.merge(key, 1, Integer::sum);
            incidents.raise(key, Incidents.Kind.SPIKE, streak >= 2 ? "warn" : "info", agent, null, now, "A " + kind + " call to " + name + " was unusually slow",
                    List.of(Incidents.evidence("latency_spike", millis, Math.round(t.spikeFactor() * priorP50), "took " + millis + " ms; the usual (median) is " + priorP50 + " ms over " + priorCount + " calls")));
        } else {
            spikeStreak.remove(key);
            incidents.close(key, now, "back to the usual speed");
        }
    }

    // ------------------------------------------------------------------------------ pauses

    void onPaused(String reason, String resumeAt, long now) {
        pausedAt = now;
        pausedReason = reason;
        pausedResumeAt = resumeAt;
        refreshPaused(now);
    }

    void onResumed(long now) {
        if (pausedAt != 0) incidents.close("paused", now, "the run resumed");
        pausedAt = 0;
    }

    /** A pause is judged by how long it has lasted, which changes with the clock, so the view asks again. */
    void refreshPaused(long nowNanos) {
        if (pausedAt == 0) return;
        long seconds = Math.max(0, (nowNanos - pausedAt) / 1_000_000_000L);
        long limit = pausedLimit();
        boolean long_ = seconds > limit;
        String detail = "paused by " + pausedReason + (pausedResumeAt == null ? "" : ", resumes " + pausedResumeAt) + ". This is not a failure.";
        incidents.raise("paused", Incidents.Kind.PAUSED, long_ ? "warn" : "info", null, null, pausedAt,
                "The run is paused (" + pausedReason + ")",
                List.of(Incidents.evidence("paused_longer_than", seconds + "s", limit + "s", detail)));
    }

    // ------------------------------------------------------------------------------ budget

    void onBudget(String name, String kind, String text, long now) {
        boolean out = kind != null && !kind.equalsIgnoreCase("WARN") && !kind.equalsIgnoreCase("WARNING");
        incidents.raise("budget:" + name, Incidents.Kind.BUDGET, out ? "high" : "warn", null, null, now,
                out ? "A budget ran out: " + name : "A budget is nearly spent: " + name,
                List.of(Incidents.evidence("budget", kind, null, text)));
    }

    // ------------------------------------------------------------------------------ the end

    /** The run ended for good (completed, failed, stopped): what was open is over. A pause that only suspends the run stays. */
    void onRunEnd(long now) {
        incidents.closeAll("", now, "the run ended");
        pausedAt = 0;
    }
}
