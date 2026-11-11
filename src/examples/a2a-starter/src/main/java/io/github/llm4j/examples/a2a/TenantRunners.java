package io.github.llm4j.examples.a2a;

import io.github.llm4j.a2a.TaskRunner;
import io.github.llm4j.a2a.TaskRunners;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Says which runner serves a caller's scope key. llm4j never interprets a tenant: a scope key is an
 * opaque string you choose (a user, a tenant, an API client).
 *
 * <p>YOUR DECISION: isolation. Filtering tasks by scope key is defence in depth and not isolation.
 * Callers who must not see each other's data need <b>a host each</b>, with its own journal, files,
 * memory, secrets and budgets; this class is where you give each one theirs.
 */
public final class TenantRunners implements TaskRunners {

    private final Map<String, TaskRunner> byScope = new ConcurrentHashMap<>();
    private final Function<String, TaskRunner> create;

    /** @param create builds the runner (and its host) for a scope the first time it is seen */
    public TenantRunners(Function<String, TaskRunner> create) {
        this.create = create;
    }

    @Override
    public TaskRunner forScope(String scope) {
        return byScope.computeIfAbsent(scope, create);
    }
}
