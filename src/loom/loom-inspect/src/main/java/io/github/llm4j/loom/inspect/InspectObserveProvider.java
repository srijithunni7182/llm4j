package io.github.llm4j.loom.inspect;

import io.github.llm4j.loom.observe.ObserveOptions;
import io.github.llm4j.loom.observe.ObserveProvider;
import java.io.PrintStream;

/** The loom-inspect add-on, found by {@code weave --observe} through {@link java.util.ServiceLoader}. */
public final class InspectObserveProvider implements ObserveProvider {

    @Override
    public String name() {
        return "loom-inspect";
    }

    @Override
    public Session open(ObserveOptions options, PrintStream out) {
        return new InspectSession(options);
    }
}
