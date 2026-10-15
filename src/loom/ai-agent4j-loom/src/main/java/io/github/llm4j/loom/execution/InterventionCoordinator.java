package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.RunHandle;
import io.github.llm4j.agent.StopReport;
import io.github.llm4j.loom.runtime.RunJournal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The agents that are running right now, each with the {@link RunHandle} that can steer, pause, wrap up or cancel it, and the record of what was done
 * to them. A delegate step opens a handle for the agent it runs and closes it when the agent returns; whoever wants to intervene (a step timeout, an
 * operator, a chat reply, a supervision rule) reaches the running agent here. Targets are a step id, an agent name (every running step of that
 * agent), or {@code *} (all).
 *
 * <p>Everything done here is journaled under the step ({@code <step>#steer.N}, {@code <step>#stop.N}), traced and audited, so a resumed run knows what the
 * person said and what happened. A steer is checked by the agent's guard and limited in size before it reaches the agent.
 */
final class InterventionCoordinator {

    /** The longest steer accepted, in characters. */
    static final int MAX_STEER_CHARS = 2000;

    /** One running agent. */
    record Active(String stepId, String agent, RunHandle handle, List<io.github.llm4j.loom.ast.RewindStmt> rewinds) { }

    /** What the coordinator needs from the run. */
    interface Host {
        RunJournal journal();

        void trace(String type, String agent, String text, Map<String, Object> data);

        void audit(String event, Map<String, Object> data);

        Instant now();

        /** The agent's guard, or null when it has none. */
        AgentGuard guard(String agent);
        /** Whether the script declares this agent {@code steerable: true}. */
        boolean steerable(String agent);
        /** The checkpoints the script declares, and {@code start}. */
        java.util.Set<String> declaredCheckpoints();
    }

    private final Host host;
    private final Map<String, Active> active = new ConcurrentHashMap<>();
    /** Why each cancelled step was cancelled, and the outcome whoever cancelled it named, if any; kept until the step reads them. */
    private final Map<String, StopOutcome> named = new ConcurrentHashMap<>();
    /** The checkpoint a person sent a cancelled step back to. */
    private final Map<String, String> backTo = new ConcurrentHashMap<>();

    InterventionCoordinator(Host host) {
        this.host = host;
    }

    /** Starts tracking the agent a step is about to run; returns the handle to run it with. */
    RunHandle open(String stepId, String agent, List<io.github.llm4j.loom.ast.RewindStmt> rewinds) {
        RunHandle handle = new RunHandle();
        active.put(stepId, new Active(stepId, agent, handle, rewinds));
        named.remove(stepId);
        backTo.remove(stepId);
        return handle;
    }

    /** The agent of this step has returned. */
    void close(String stepId) {
        active.remove(stepId);
    }

    /** The agents running now. */
    List<Active> running() {
        return List.copyOf(active.values());
    }

    private List<Active> matching(String target) {
        List<Active> out = new ArrayList<>();
        for (Active a : active.values()) {
            if ("*".equals(target) || a.stepId().equals(target) || a.agent().equals(target)) out.add(a);
        }
        return out;
    }

    // ---- what a person or a rule can do ----

    /**
     * Sends a message to the running agents named by {@code target}. Returns how many took it; 0 when none is running, the text is empty or too long, or the
     * agent's guard refused it. The text is journaled under the step so a restarted step is told what was said.
     */
    synchronized int steer(String target, String text, String origin) {
        return steer(target, text, origin, true);
    }

    /** As above; {@code byPerson} false is a steer the script itself declared (a supervise rule), which needs no {@code steerable: true}. */
    private synchronized int steer(String target, String text, String origin, boolean byPerson) {
        if (text == null || text.isBlank()) return 0;
        String message = text.strip();
        if (message.length() > MAX_STEER_CHARS) {
            record("steer_refused", target, null, Map.of("origin", String.valueOf(origin), "why", "longer than " + MAX_STEER_CHARS + " characters"));
            return 0;
        }
        int n = 0;
        for (Active a : matching(target)) {
            if (byPerson && !host.steerable(a.agent())) {
                record("steer_refused", a.agent(), a.stepId(), Map.of("origin", String.valueOf(origin), "why", "the agent is not declared steerable"));
                continue;
            }
            AgentGuard guard = host.guard(a.agent());
            if (guard != null) {
                try {
                    guard.checkTask(message);
                } catch (StepFailure refused) {
                    record("steer_refused", a.agent(), a.stepId(), Map.of("origin", String.valueOf(origin), "why", refused.getMessage()));
                    continue;
                }
            }
            a.handle().steer(message, origin);
            int count = steersFor(a.stepId()).size() + 1;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("text", message);
            entry.put("origin", origin == null ? "" : origin);
            entry.put("at", host.now().toString());
            host.journal().put(a.stepId() + "#steer." + count, new RunJournal.Entry("steer", entry));
            record("steer", a.agent(), a.stepId(), Map.of("origin", String.valueOf(origin), "chars", message.length(), "text", message));
            n++;
        }
        return n;
    }

    /** Cancels the running agents named by {@code target}; {@code outcome} is what the run should do next, or null to leave it to the defaults. */
    int cancel(String target, String reason, String origin, StopOutcome outcome) {
        int n = 0;
        for (Active a : matching(target)) {
            if (outcome != null) named.put(a.stepId(), outcome);
            a.handle().cancel(reason, origin);
            record("cancel", a.agent(), a.stepId(), mapOf("reason", reason, "origin", origin, "outcome", outcome == null ? null : outcome.name()));
            n++;
        }
        return n;
    }

    /**
     * Cancels the running agents named by {@code target} and sends the run back to {@code checkpoint}, carrying {@code reason}. Refused (and traced, with
     * the checkpoints there are) when the script does not declare the checkpoint, or when the step has no {@code on_stopped} rewind to take its limits from.
     */
    int cancelBack(String target, String checkpoint, String reason, String origin) {
        int n = 0;
        java.util.Set<String> declared = host.declaredCheckpoints();
        for (Active a : matching(target)) {
            String why = !declared.contains(checkpoint) ? "the script declares no checkpoint named " + checkpoint + " (it declares: " + String.join(", ", declared) + ")"
                    : a.rewinds().isEmpty() ? "the step has no on_stopped { rewind ... } that allows going back" : null;
            if (why != null) {
                record("cancel_back_refused", a.agent(), a.stepId(), mapOf("checkpoint", checkpoint, "origin", origin, "why", why));
                continue;
            }
            named.put(a.stepId(), StopOutcome.REWIND);
            backTo.put(a.stepId(), checkpoint);
            a.handle().cancel(reason, origin);
            record("cancel_back", a.agent(), a.stepId(), mapOf("reason", reason, "origin", origin, "checkpoint", checkpoint));
            n++;
        }
        return n;
    }

    /** What a {@code supervise} rule does for the step it watches. Each is traced as a supervision, and journaled and audited like what a person does. */
    Supervision.Actor supervisor() {
        return new Supervision.Actor() {
            @Override
            public void steer(String stepId, String agent, String text, String rule) {
                record("supervision", agent, stepId, mapOf("rule", rule, "does", "steer"));
                InterventionCoordinator.this.steer(stepId, text, "supervision", false);
            }

            @Override
            public void wrapUp(String stepId, String agent, String rule) {
                record("supervision", agent, stepId, mapOf("rule", rule, "does", "wrap_up"));
                InterventionCoordinator.this.wrapUp(stepId, "supervision", "supervision");
            }

            @Override
            public void cancel(String stepId, String agent, StopOutcome outcome, String rule) {
                record("supervision", agent, stepId, mapOf("rule", rule, "does", "cancel", "outcome", outcome == null ? null : outcome.name()));
                InterventionCoordinator.this.cancel(stepId, "supervision", "supervision", outcome);
            }
        };
    }

    /** The checkpoint a person sent this step back to, or null. */
    String backTo(String stepId) {
        return backTo.get(stepId);
    }

    /** Asks the running agents named by {@code target} to finish now with what they have. */
    int wrapUp(String target, String reason, String origin) {
        int n = 0;
        for (Active a : matching(target)) {
            a.handle().wrapUp(reason, origin);
            record("wrap_up", a.agent(), a.stepId(), mapOf("reason", reason, "origin", origin));
            n++;
        }
        return n;
    }

    int pause(String target, String origin) {
        int n = 0;
        for (Active a : matching(target)) {
            a.handle().pause();
            record("pause", a.agent(), a.stepId(), mapOf("origin", origin));
            n++;
        }
        return n;
    }

    int resume(String target, String origin) {
        int n = 0;
        for (Active a : matching(target)) {
            a.handle().resume();
            record("resume", a.agent(), a.stepId(), mapOf("origin", origin));
            n++;
        }
        return n;
    }

    // ---- what the step reads ----

    /** The outcome whoever cancelled the step named, or null. */
    StopOutcome namedOutcome(String stepId) {
        return named.get(stepId);
    }

    /** What the person said to this step so far, oldest first (from the journal, so it survives a restart). */
    List<String> steersFor(String stepId) {
        List<String> out = new ArrayList<>();
        Map<Integer, String> byNumber = new java.util.TreeMap<>();
        String prefix = stepId + "#steer.";
        for (Map.Entry<String, RunJournal.Entry> e : host.journal().all().entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            try {
                int n = Integer.parseInt(e.getKey().substring(prefix.length()));
                if (e.getValue().value() instanceof Map<?, ?> m && m.get("text") != null) byNumber.put(n, String.valueOf(m.get("text")));
            } catch (NumberFormatException ignored) {
                // not one of ours
            }
        }
        out.addAll(byNumber.values());
        return out;
    }

    /** Writes down that a step was stopped, and what was decided about it. */
    synchronized void recordStop(String stepId, String agent, StopReport stop, StopOutcome outcome, String why) {
        int n = 1;
        while (host.journal().get(stepId + "#stop." + n).isPresent()) n++;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", stop.kind().name());
        value.put("reason", stop.reason() == null ? "" : stop.reason());
        value.put("origin", stop.origin() == null ? "" : stop.origin());
        value.put("phase", stop.phase().name());
        value.put("changed", stop.mayHaveChangedThings());
        value.put("outcome", outcome.name());
        value.put("why", why);
        value.put("at", host.now().toString());
        host.journal().put(stepId + "#stop." + n, new RunJournal.Entry("stop", value));
        Map<String, Object> data = new LinkedHashMap<>(value);
        data.put("step", stepId);
        data.put("category", StopHandlers.category(stop)); // the reason as a script sees it
        host.audit("step_stopped", data);
        host.trace(TraceEvent.STEP_STOPPED, agent, "stopped (" + value.get("reason") + "): " + outcome.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '), data);
    }

    private void record(String action, String agent, String stepId, Map<String, Object> details) {
        Map<String, Object> data = new LinkedHashMap<>(details);
        data.put("action", action);
        if (stepId != null) data.put("step", stepId);
        host.audit("intervention", data);
        host.trace(TraceEvent.INTERVENTION, agent, action.replace('_', ' ') + (agent == null ? "" : " " + agent), data);
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
