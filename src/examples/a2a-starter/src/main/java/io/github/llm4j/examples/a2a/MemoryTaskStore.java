package io.github.llm4j.examples.a2a;

import io.github.llm4j.a2a.TaskRunner;
import io.github.llm4j.a2a.TaskStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where tasks live between requests. Memory is enough to try things.
 *
 * <p>YOUR DECISION: a task that must survive a restart needs a durable store (a database, files
 * beside the journal), and a task that was working when the process died must be restarted or failed
 * at startup. Keep the store beside the run journal and the received-files folder: they belong
 * together.
 */
public final class MemoryTaskStore implements TaskStore {

    private final Map<String, TaskRunner.OwnedTask> tasks = new ConcurrentHashMap<>();

    @Override
    public void save(TaskRunner.OwnedTask task) {
        tasks.put(task.task().id(), task);
    }

    @Override
    public Optional<TaskRunner.OwnedTask> find(String scope, String taskId) {
        return Optional.ofNullable(tasks.get(taskId)).filter(t -> t.scope().equals(scope));
    }

    @Override
    public List<TaskRunner.OwnedTask> list(String scope) {
        return tasks.values().stream().filter(t -> t.scope().equals(scope)).toList();
    }
}
