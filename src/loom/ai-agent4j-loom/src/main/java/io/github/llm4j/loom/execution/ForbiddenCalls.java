package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.ToolHook;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The names a supervisor has forbidden a child to call ({@code assert never calls <name>}, decision
 * D24). A forbidden call is refused <em>before</em> it runs, whether it is a tool an agent reasons
 * its way to, a task, an agent or a {@code use} step, so the effect never happens; the refusal is
 * counted for the supervisor to see. It only narrows what the child may do.
 */
final class ForbiddenCalls {

    private final Set<String> names = ConcurrentHashMap.newKeySet();
    private final Trajectory trajectory;

    ForbiddenCalls(Trajectory trajectory) {
        this.trajectory = trajectory;
    }

    void forbid(String name) {
        names.add(name);
    }

    /** A re-run of the child keeps what its supervisor forbade: authority only narrows. */
    void carry(ForbiddenCalls earlier) {
        names.addAll(earlier.names);
    }

    /**
     * Counts and refuses the call of {@code name} if it is forbidden.
     *
     * @throws StepFailure if the call is forbidden, so the step fails like any other refused step
     */
    void require(String name) {
        if (!names.contains(name)) return;
        trajectory.refused(name);
        throw new StepFailure(message(name), null);
    }

    private static String message(String name) {
        return name + " was refused: the run's supervisor forbids this workflow to call it";
    }

    /** The hook that stops an agent's tool call. */
    ToolHook hook() {
        return new ToolHook() {
            @Override
            public Decision beforeCall(String toolName, Map<String, Object> arguments) {
                if (!names.contains(toolName)) return Decision.allow();
                trajectory.refused(toolName);
                return Decision.reject(message(toolName));
            }
        };
    }
}
