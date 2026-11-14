package io.github.llm4j.tools;

import io.github.llm4j.agent.tool.Isolation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A backend that never runs a program (D15): it records each request and answers from a script, so
 * a workflow that uses a {@code sandbox} can be tested with no container, no process and no
 * network. It reports the isolation it was made with, so a test can also check what happens when a
 * backend reports less than was asked.
 */
public final class FakeSandbox implements SandboxBackend {

    /** Every request, in order. */
    public final List<SandboxRequest> requests = Collections.synchronizedList(new ArrayList<>());

    /** Every handle stopped, in order. */
    public final List<String> stopped = Collections.synchronizedList(new ArrayList<>());

    private final Set<String> live = Collections.synchronizedSet(new LinkedHashSet<>());
    private final AtomicInteger served = new AtomicInteger();
    private final Isolation isolation;
    private final Set<Limit> enforces;
    private volatile Function<SandboxRequest, SandboxResult> answers;
    private volatile String missing;

    /** A fake that reports {@code isolation}, enforces every limit and answers "ok" with exit 0. */
    public FakeSandbox(Isolation isolation) {
        this(isolation, EnumSet.allOf(Limit.class));
    }

    /** As above, enforcing only {@code enforces}. */
    public FakeSandbox(Isolation isolation, Set<Limit> enforces) {
        this.isolation = isolation;
        this.enforces = Set.copyOf(enforces);
        this.answers = r -> new SandboxResult(0, false, "ok", "", false, 0, isolation, 1);
    }

    /** Answers every request from {@code answers}. */
    public FakeSandbox answering(Function<SandboxRequest, SandboxResult> answers) {
        this.answers = answers;
        return this;
    }

    /** Makes the probe report {@code what} as missing (a runtime that is not there). */
    public FakeSandbox missing(String what) {
        this.missing = what;
        return this;
    }

    /** Pretends a handle is running, as a crashed run might have left it. */
    public FakeSandbox leftRunning(String handle) {
        live.add(handle);
        return this;
    }

    @Override
    public Isolation isolation() {
        return isolation;
    }

    @Override
    public Set<Limit> enforces() {
        return enforces;
    }

    @Override
    public String probe() {
        return missing;
    }

    @Override
    public SandboxResult run(SandboxRequest request) {
        requests.add(request);
        return answers.apply(request);
    }

    @Override
    public boolean canServe() {
        return true;
    }

    @Override
    public Served serve(SandboxRequest request, int port) {
        requests.add(request);
        String handle = request.name();
        live.add(handle);
        return new Served(handle, 40000 + served.incrementAndGet());
    }

    @Override
    public void stop(String handle) {
        stopped.add(handle);
        live.remove(handle);
    }

    @Override
    public boolean running(String handle) {
        return live.contains(handle);
    }
}
