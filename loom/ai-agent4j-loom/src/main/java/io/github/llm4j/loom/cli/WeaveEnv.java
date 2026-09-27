package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.trigger.system.CommandRunner;
import io.github.llm4j.ratelimit.Sleeper;
import java.io.PrintStream;
import java.time.Clock;
import java.util.List;

/**
 * What the {@code weave} commands use from the outside world, so tests can replace every piece: models,
 * the person at the console, output, time, waiting and system commands.
 *
 * @param weave the command that runs weave, written into system triggers
 */
record WeaveEnv(LLMClientFactory models, HumanInterface human, PrintStream out, PrintStream err, Clock clock,
                Sleeper sleeper, CommandRunner commands, List<String> weave,
                java.util.function.Function<String, String> env) {

    WeaveEnv(LLMClientFactory models, HumanInterface human, PrintStream out, PrintStream err, Clock clock,
             Sleeper sleeper, CommandRunner commands, List<String> weave) {
        this(models, human, out, err, clock, sleeper, commands, weave, System::getenv);
    }

    static WeaveEnv system() {
        return new WeaveEnv(new DefaultLLMClientFactory(), new ConsoleHumanInterface(), System.out, System.err,
                Clock.systemDefaultZone(), Sleeper.SYSTEM, CommandRunner.SYSTEM, selfCommand());
    }

    WeaveEnv withWeave(List<String> command) {
        return new WeaveEnv(models, human, out, err, clock, sleeper, commands, command, env);
    }

    /** {@code java -cp <this classpath> io.github.llm4j.loom.cli.WeaveCLI}: runs this weave again later. */
    static List<String> selfCommand() {
        String java = ProcessHandle.current().info().command().orElse("java");
        return List.of(java, "-cp", System.getProperty("java.class.path"), WeaveCLI.class.getName());
    }
}
