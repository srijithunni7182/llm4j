package io.github.llm4j.loom.cli;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What {@code weave fabric} needs from the machine: the jar, other processes, the network, the
 * clock. {@link FabricLauncher} decides; this does, so the decisions can be tested without a
 * server.
 */
interface FabricHost {

    /** A finished command: its exit code and what it printed. */
    record Result(int exit, String output) {}

    /**
     * The Fabric jar, or empty.
     *
     * @throws IllegalArgumentException when the {@code FABRIC_JAR} variable names a non-file
     */
    Optional<Path> jar();

    /** The program that runs a jar. */
    String java();

    /** Runs a command to its end with {@code extraEnvironment} added to this process's. */
    Result run(List<String> command, Map<String, String> extraEnvironment);

    /**
     * Starts a command that outlives this one, its output in {@code log}. The admin password
     * variable is not passed on: a server holds only the hash.
     *
     * @return the process id
     */
    long startBackground(List<String> command, Path log);

    /** Whether the server answers at {@code baseUrl}, tried for up to {@code seconds}. */
    boolean answers(String baseUrl, int seconds);

    /** Whether a process with this id is running. */
    boolean alive(long pid);

    /**
     * Whether that process is a Fabric server, judged by its command line; false when it cannot be
     * told. A recycled process id must never get a pid file's {@code stop}.
     */
    boolean looksLikeFabric(long pid);

    /** Asks the process to end and waits a few seconds for it to. */
    void stop(long pid);

    /** Ends the process at once. */
    void kill(long pid);

    Clock clock();

    /** An environment variable, or null. */
    String environment(String name);
}
