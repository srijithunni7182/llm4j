package io.github.llm4j.loom.host;

import io.github.llm4j.a2a.A2ATransport;
import io.github.llm4j.a2a.AgentChannel;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Loom side of waiting for a remote agent (docs/specs/32-a2a-integration, R17): a run that calls a
 * remote agent holds no thread while the task works, so something has to say when to look again. The
 * host's poller, stream or webhook calls {@link #taskChanged}, which resumes the run. llm4j never polls,
 * sleeps or listens for a notification itself.
 *
 * <p>A push notification is delivered at least once, so {@code taskChanged} is idempotent: a repeat,
 * or a stale one for a run that has moved on (it is not suspended, or waits on another task), changes
 * nothing.
 *
 * <p>{@link LoomHost} is not changed: the transport and the channels reach the runs through {@link
 * #wiring}, which goes into {@code LoomHost.Builder.customizer}.
 */
public final class RemoteTasks {

    private final LoomHost host;

    /**
     * Creates the adapter for a host.
     *
     * @param host the host whose runs call remote agents
     */
    public RemoteTasks(LoomHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * What gives every run of a host its transport and its channels; pass it to {@code
     * LoomHost.Builder.customizer}.
     *
     * @param transport how remote agents are reached
     * @param channels the implementation of each declared {@code agent_channel}, by name
     */
    public static Consumer<HarnessExecutor> wiring(A2ATransport transport, Map<String, AgentChannel> channels) {
        Objects.requireNonNull(transport, "transport");
        Map<String, AgentChannel> copy = Map.copyOf(channels);
        return executor -> {
            executor.setA2ATransport(transport);
            copy.forEach(executor::setAgentChannel);
        };
    }

    /**
     * Tells the host that a remote task changed, and carries the run on if it was waiting for it.
     * Runs the workflow on the calling thread until it finishes or pauses again.
     *
     * @param runId the run
     * @param taskId the remote task that changed
     * @return the outcome of carrying the run on, or empty if there was nothing to do (a repeat, a
     *     stale notification, an unknown run, or another thread is already carrying it on)
     */
    public Optional<RunOutcome> taskChanged(String runId, String taskId) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(taskId, "taskId");
        Optional<RunRecord> found = host.run(runId);
        if (found.isEmpty()) return Optional.empty();
        RunRecord record = found.get();
        boolean waitingOnThisTask =
                record.status() == RunRecord.Status.SUSPENDED
                        && record.reason() == RunSuspended.Reason.REMOTE
                        && record.detail() != null
                        && record.detail().contains("task " + taskId + ")");
        if (!waitingOnThisTask) return Optional.empty();
        try {
            return Optional.of(host.resume(runId, null));
        } catch (IllegalStateException alreadyBeingResumed) {
            return Optional.empty();
        }
    }
}
