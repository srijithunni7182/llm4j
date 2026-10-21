package io.github.llm4j.loom.invoker;

import io.github.llm4j.secret.InvokerCredentials;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A host's {@link InvokerCredentials} for tests: hands out the access tokens it was given in order
 * (the last one again and again), as a host that refreshes an expiring token would, and records
 * what it was asked.
 */
final class Tokens implements InvokerCredentials {

    private final List<String> tokens;
    final AtomicInteger gets = new AtomicInteger();
    final List<String> invalidated = new CopyOnWriteArrayList<>();

    Tokens(String... tokens) {
        this.tokens = List.of(tokens);
    }

    @Override
    public String get(String name) {
        return "token".equals(name)
                ? tokens.get(Math.min(gets.getAndIncrement(), tokens.size() - 1))
                : null;
    }

    @Override
    public void invalidate(String name) {
        invalidated.add(name);
    }

    @Override
    public boolean provides(String name) {
        return "token".equals(name);
    }
}
