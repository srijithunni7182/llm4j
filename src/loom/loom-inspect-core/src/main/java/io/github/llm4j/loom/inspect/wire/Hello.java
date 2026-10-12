package io.github.llm4j.loom.inspect.wire;

import java.util.List;

/**
 * Sent once when a process connects to a server.
 *
 * @param client the {@code --observe-name}
 * @param instance an id for this start of the process
 * @param wire the {@link Wire#VERSION} this process speaks
 * @param workflows the workflows its script defines
 */
public record Hello(String client, String instance, String host, long pid, String loomVersion, String inspectVersion, int wire,
                    List<Workflow> workflows) {

    /** A workflow the process knows, and a hash of its script so the same workflow in two processes can be told from a different one. */
    public record Workflow(String name, String scriptHash) { }

    public Hello {
        workflows = workflows == null ? List.of() : List.copyOf(workflows);
    }
}
