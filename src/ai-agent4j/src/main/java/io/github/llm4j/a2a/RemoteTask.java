package io.github.llm4j.a2a;

import java.util.List;
import java.util.Objects;

/**
 * What llm4j knows about a task on a remote agent.
 *
 * @param id the id the remote made
 * @param contextId the context, possibly empty
 * @param state the state; {@link TaskState#UNKNOWN} when not recognised
 * @param statusMessage the question or explanation that came with the state, or null
 * @param artifacts the outputs so far
 * @param history the messages the remote returned, possibly cut short; may be empty
 */
public record RemoteTask(
        String id,
        String contextId,
        TaskState state,
        A2AMessage statusMessage,
        List<Artifact> artifacts,
        List<A2AMessage> history) {

    /** Requires an id and a state; copies the lists. */
    public RemoteTask {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("task id must not be blank");
        }
        contextId = contextId == null ? "" : contextId;
        Objects.requireNonNull(state, "state");
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** A task with only an id, a context and a state. */
    public static RemoteTask of(String id, String contextId, TaskState state) {
        return new RemoteTask(id, contextId, state, null, List.of(), List.of());
    }

    /** Whether the task has finished. */
    public boolean isTerminal() {
        return state.isTerminal();
    }
}
