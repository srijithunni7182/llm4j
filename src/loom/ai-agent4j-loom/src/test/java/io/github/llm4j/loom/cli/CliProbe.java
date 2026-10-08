package io.github.llm4j.loom.cli;

import java.io.File;
import java.io.PrintStream;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Lets tests in other packages run CLI commands with a controlled environment. */
public final class CliProbe {

    private CliProbe() { }

    public static int check(File script, boolean lenient, PrintStream out, Function<String, String> env,
                            AtomicInteger modelClients) {
        WeaveEnv e = new WeaveEnv(model -> {
            modelClients.incrementAndGet();
            throw new IllegalStateException("no models in check");
        }, message -> "", out, out, Clock.systemUTC(), d -> { }, c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"), env);
        return WeaveCLI.check(script, null, lenient, e);
    }

    /** {@code weave graph} writing to {@code out}; nothing else is reachable from it. */
    public static int graph(File script, String format, String workflow, PrintStream out) {
        WeaveEnv e = new WeaveEnv(model -> { throw new IllegalStateException("no models in graph"); }, message -> "", out, out,
                Clock.systemUTC(), d -> { }, c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"));
        GraphCommand command = new GraphCommand();
        command.script = script;
        command.format = format;
        command.workflow = workflow;
        return GraphCommand.graph(command, e);
    }

    /** {@code weave check} with a given client factory (to see which model names it accepts). */
    public static int check(File script, PrintStream out, Function<String, String> env,
                            io.github.llm4j.loom.execution.LLMClientFactory models) {
        WeaveEnv e = new WeaveEnv(models, message -> "", out, out, Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), env);
        return WeaveCLI.check(script, null, false, e);
    }

    /** {@code weave run [--trace[=json]]} with scripted models. */
    public static int run(File script, String trace, PrintStream out, PrintStream err, Function<String, String> env,
                          io.github.llm4j.loom.execution.LLMClientFactory models) {
        WeaveEnv e = new WeaveEnv(models, message -> "yes", out, err, Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), env);
        return WeaveCLI.run(script, null, "Main", java.util.Map.of(), null, null, null, null, null, null, false, false, trace, e);
    }
}
