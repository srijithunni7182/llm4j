package io.github.llm4j.agent.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tasks a host makes available to workflows, by name. Tasks are operator-supplied code: a script can name one in a {@code run}
 * statement but can never cause one to be loaded.
 *
 * <p>{@link #discovered()} reads {@code META-INF/services/io.github.llm4j.agent.task.Task} files on the classpath, so putting a jar of
 * tasks beside the {@code weave} CLI is enough to make them available.
 */
public final class TaskRegistry {

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    /**
     * Adds a task. A name already taken is an error: a deterministic step must never be replaced silently.
     *
     * @return this registry
     */
    public TaskRegistry register(Task task) {
        Objects.requireNonNull(task, "task");
        String name = task.getName();
        if (!Task.isValidName(name)) {
            throw new IllegalArgumentException("task name \"" + name + "\" must be a letter followed by letters, digits, _ or -");
        }
        if (tasks.putIfAbsent(name, task) != null) {
            throw new IllegalArgumentException("a task named " + name + " is already registered");
        }
        return this;
    }

    /** The task, or null. */
    public Task get(String name) {
        return name == null ? null : tasks.get(name);
    }

    public boolean contains(String name) {
        return get(name) != null;
    }

    /** The registered names, sorted. */
    public Set<String> names() {
        return Collections.unmodifiableSet(new TreeSet<>(tasks.keySet()));
    }

    public List<Task> all() {
        List<Task> out = new ArrayList<>(tasks.values());
        out.sort(java.util.Comparator.comparing(Task::getName));
        return Collections.unmodifiableList(out);
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    /** A registry holding every {@link Task} the class path declares as a service (the context class loader, else this library's). */
    public static TaskRegistry discovered() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return discovered(loader != null ? loader : TaskRegistry.class.getClassLoader());
    }

    /** A registry holding every {@link Task} the given class loader declares as a service. Two with the same name is an error. */
    public static TaskRegistry discovered(ClassLoader loader) {
        TaskRegistry registry = new TaskRegistry();
        for (Task task : ServiceLoader.load(Task.class, loader)) registry.register(task);
        return registry;
    }
}
