package io.github.llm4j.loom.execution;

import io.github.llm4j.secret.CredentialSource;
import io.github.llm4j.secret.InvokerCredentials;
import io.github.llm4j.secret.SecretException;
import java.util.function.Supplier;

/**
 * One credential of whoever is running the workflow ({@code invoker.NAME} in a script), as a tool sees it. It holds no value: each {@link #get}
 * asks the run's {@link InvokerCredentials}, which belongs to the thread the tool is running on, so two runs never see each other's.
 */
final class InvokerSource implements CredentialSource {

    private final String name;
    private final Supplier<InvokerCredentials> current;

    InvokerSource(String name, Supplier<InvokerCredentials> current) {
        this.name = name;
        this.current = current;
    }

    @Override
    public String get() {
        InvokerCredentials invoker = current.get();
        if (invoker == null) throw new SecretException("this run has no invoker credentials (invoker." + name + ")");
        String value = invoker.get(name);
        if (value == null) throw new SecretException("the invoker has no credential " + name);
        return value;
    }

    @Override
    public void rejected() {
        InvokerCredentials invoker = current.get();
        if (invoker != null) invoker.invalidate(name);
    }
}
