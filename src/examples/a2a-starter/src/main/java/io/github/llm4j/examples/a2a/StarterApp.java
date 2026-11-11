package io.github.llm4j.examples.a2a;

import io.github.llm4j.a2a.A2AHandler;
import io.github.llm4j.a2a.AgentCardBuilder;
import io.github.llm4j.a2a.AgentChannel;
import io.github.llm4j.a2a.TaskRunner;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.host.ExposedCard;
import io.github.llm4j.loom.host.LoomHost;
import io.github.llm4j.loom.host.LoomTaskRunner;
import io.github.llm4j.loom.host.Persistence;
import io.github.llm4j.loom.host.RemoteTasks;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Puts the pieces together for one script: the transport and channels that let its runs call a
 * remote agent, a runner that serves its {@code expose} block, and the server in front of it.
 *
 * <p>It compiles and need not run. Read it as a map of what you provide, one decision per line.
 */
public final class StarterApp {

    private StarterApp() {}

    /**
     * Builds the serving side for the first {@code expose} block of a script.
     *
     * @param scriptFile the Loom script
     * @param storeFolder where runs, tasks and received files live (keep them together and durable)
     * @param publicAddress the https address you serve the endpoint at
     * @param port the local port
     */
    public static StarterServer build(Path scriptFile, Path storeFolder, String publicAddress, int port) throws IOException {
        LoomScript script = new LoomLoader().load(scriptFile.toString());

        // what answers a remote agent's questions (YOUR DECISION: a person's queue, a service)
        AgentChannel supplierDesk = new StarterChannel(question -> null);

        // one store for tasks; one host per tenant if tenants must not share anything
        MemoryTaskStore tasks = new MemoryTaskStore();
        LoomTaskRunner runner = new LoomTaskRunner(script.getExposes().get(0), script, tasks, Clock.systemUTC());

        LoomHost host =
                LoomHost.builder(script, new ToolRegistry(), model -> {
                            throw new IllegalStateException("this starter script uses no model of its own");
                        })
                        .persistence(Persistence.file(storeFolder))
                        .scriptRef(scriptFile.toAbsolutePath().toString())
                        .baseDir(scriptFile.toAbsolutePath().getParent())
                        .customizer(
                                RemoteTasks.wiring(new StarterTransport(), Map.of("SupplierDesk", supplierDesk))
                                        .andThen(runner.wiring()))
                        .build();
        runner.attach(host);

        // the host calls taskChanged when it learns that a remote task changed: it polls, streams or
        // receives a push (llm4j does none of that). See RemoteTasks.
        RemoteTasks remoteTasks = new RemoteTasks(host);
        assert remoteTasks != null;

        TenantRunners runners = new TenantRunners(scope -> runner); // one runner for all scopes: not isolation
        TaskRunner unused = runners.forScope("example");
        assert unused != null;

        AgentCardBuilder.Published card = ExposedCard.of(script.getExposes().get(0), script, publicAddress);
        return new StarterServer(port, new A2AHandler(runners), card);
    }
}
