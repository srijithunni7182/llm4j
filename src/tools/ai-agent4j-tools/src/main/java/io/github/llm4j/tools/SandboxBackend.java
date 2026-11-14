package io.github.llm4j.tools;

import io.github.llm4j.agent.tool.Isolation;
import java.io.IOException;
import java.util.Set;

/**
 * Where a {@code sandbox} tool runs a program (phase 35, D8). llm4j ships {@link ProcessSandbox}
 * ({@code process}), {@link ContainerSandbox} ({@code container}) and {@link FakeSandbox} for tests;
 * a consumer implements this for a virtual machine or a remote service. A backend reports the
 * isolation it applies and the limits it enforces, honestly: a tool refuses, at load, a limit its
 * backend cannot enforce, and stops a run whose reported isolation is below what the script asked.
 */
public interface SandboxBackend {

    /** The limits a backend may or may not enforce. */
    enum Limit {
        TIME,
        OUTPUT,
        MEMORY,
        PIDS,
        DISK,
        NETWORK_NONE,
        NETWORK_ALLOW,
        /** Protected paths of the workspace mounted read only. */
        READ_ONLY_PATHS
    }

    /** The isolation every run gets. */
    Isolation isolation();

    /** The limits this backend enforces. */
    Set<Limit> enforces();

    /**
     * What is missing for this backend to run anything (a runtime not installed or not started), or
     * null when it is ready. Asked when a script is checked, so a missing runtime is found before a
     * run.
     */
    String probe();

    /** Runs one program and waits for it, within the request's limits. */
    SandboxResult run(SandboxRequest request) throws IOException, InterruptedException;

    /** Whether this backend can keep a program running for a while ({@code serve}). */
    default boolean canServe() {
        return false;
    }

    /**
     * Starts a program that keeps running and listens on {@code port} inside the sandbox, published
     * on the host's loopback only.
     *
     * @return the handle and the host port it can be reached on
     */
    default Served serve(SandboxRequest request, int port) throws IOException, InterruptedException {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot serve");
    }

    /** Stops a served program; stopping one that is gone is not an error. */
    default void stop(String handle) throws IOException, InterruptedException {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot serve");
    }

    /** Whether a served program is still running. */
    default boolean running(String handle) throws IOException, InterruptedException {
        return false;
    }

    /** A served program: its handle (a container's name) and the loopback port it answers on. */
    record Served(String handle, int hostPort) {}
}
