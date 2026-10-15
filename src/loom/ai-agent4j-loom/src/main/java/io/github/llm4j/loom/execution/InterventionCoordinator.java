package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.RunHandle;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The agents that are running right now, each with the {@link RunHandle} that can steer, pause or cancel it. A delegate step opens a handle for
 * the agent it runs and closes it when the agent returns; anything that wants to intervene (a step timeout today; an operator, a chat reply or a
 * supervision rule later) reaches the running agent here. Targets are a step id, or an agent name (every running step of that agent).
 */
final class InterventionCoordinator {

    /** One running agent. */
    record Active(String stepId, String agent, RunHandle handle) { }

    private final Map<String, Active> active = new ConcurrentHashMap<>();
    /** Why each cancelled step was cancelled, kept until the step reads it. */
    private final Map<String, String> reasons = new ConcurrentHashMap<>();

    /** Starts tracking the agent a step is about to run; returns the handle to run it with. */
    RunHandle open(String stepId, String agent) {
        RunHandle handle = new RunHandle();
        active.put(stepId, new Active(stepId, agent, handle));
        reasons.remove(stepId);
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

    /** Cancels the running steps named by {@code target} (a step id, or an agent name); returns how many were asked to stop. */
    int cancel(String target, String reason) {
        int n = 0;
        for (Active a : active.values()) {
            if (a.stepId().equals(target) || a.agent().equals(target)) {
                reasons.put(a.stepId(), reason == null ? "" : reason);
                a.handle().cancel();
                n++;
            }
        }
        return n;
    }

    /** Why the step was cancelled, or null when it was not. */
    String reasonFor(String stepId) {
        return reasons.get(stepId);
    }
}
