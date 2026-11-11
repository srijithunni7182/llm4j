package io.github.llm4j.a2a;

import java.util.List;
import java.util.Optional;

/**
 * Where an {@link AgentTaskRunner} keeps tasks between requests. The consumer supplies it: memory is
 * enough to try things, but a task that must survive a restart needs a durable store, and the
 * consumer restarts or fails unfinished tasks at startup (llm4j keeps no journal for a plain agent).
 */
public interface TaskStore {

    /** Inserts or replaces a task. */
    void save(TaskRunner.OwnedTask task);

    /** A task of this scope, or empty. */
    Optional<TaskRunner.OwnedTask> find(String scope, String taskId);

    /** Every task of this scope. */
    List<TaskRunner.OwnedTask> list(String scope);
}
