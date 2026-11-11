package io.github.llm4j.a2a;

/**
 * Who is calling, as one opaque scope key the host chooses (a user, a tenant, an API client). The
 * host authenticates the request; llm4j only enforces that a task is visible to the scope that
 * created it. It is defence in depth and not isolation: a consumer who needs isolation gives each
 * scope its own host.
 *
 * @param scopeKey the key; never blank
 */
public record CallerIdentity(String scopeKey) {
    /** Requires a non-blank key. */
    public CallerIdentity {
        if (scopeKey == null || scopeKey.isBlank()) {
            throw new IllegalArgumentException("a caller identity needs a non-blank scope key");
        }
    }
}
