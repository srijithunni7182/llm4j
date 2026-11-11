package io.github.llm4j.a2a;

/**
 * Says which {@link TaskRunner} serves a scope, so a consumer who needs isolation can give each tenant
 * its own host, with its own journal, files, memory, secrets and budgets. llm4j never interprets a
 * tenant.
 */
@FunctionalInterface
public interface TaskRunners {

    /** The runner for a scope key; never null. */
    TaskRunner forScope(String scope);
}
