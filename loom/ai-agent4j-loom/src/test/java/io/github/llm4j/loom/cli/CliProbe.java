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
}
