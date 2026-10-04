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
                java.util.function.Function<String, String> env, String askVia,
                io.github.llm4j.secret.SecretStore secrets) {

    WeaveEnv(LLMClientFactory models, HumanInterface human, PrintStream out, PrintStream err, Clock clock,
             Sleeper sleeper, CommandRunner commands, List<String> weave,
             java.util.function.Function<String, String> env, String askVia) {
        this(models, human, out, err, clock, sleeper, commands, weave, env, askVia, null);
    }

    WeaveEnv(LLMClientFactory models, HumanInterface human, PrintStream out, PrintStream err, Clock clock,
             Sleeper sleeper, CommandRunner commands, List<String> weave) {
        this(models, human, out, err, clock, sleeper, commands, weave, System::getenv, null);
    }

    WeaveEnv(LLMClientFactory models, HumanInterface human, PrintStream out, PrintStream err, Clock clock,
             Sleeper sleeper, CommandRunner commands, List<String> weave, java.util.function.Function<String, String> env) {
        this(models, human, out, err, clock, sleeper, commands, weave, env, null);
    }

    /** The same, asking through a channel ({@code --ask-via}) whatever the store says. */
    WeaveEnv withAskVia(String channel) {
        return channel == null ? this : new WeaveEnv(models, human, out, err, clock, sleeper, commands, weave, env, channel, secrets);
    }

    /**
     * The same, with a secret store the commands hand to the executors they build. The default model factory is rebuilt to look keys up in the store
     * first; a factory a test or host supplied is left alone.
     */
    WeaveEnv withSecrets(io.github.llm4j.secret.SecretStore store) {
        if (store == null) return this;
        LLMClientFactory factory = models instanceof io.github.llm4j.loom.execution.DefaultLLMClientFactory
                ? new io.github.llm4j.loom.execution.DefaultLLMClientFactory(env, store) : models;
        return new WeaveEnv(factory, human, out, err, clock, sleeper, commands, weave, env, askVia, store);
    }

    static WeaveEnv system() {
        return new WeaveEnv(new DefaultLLMClientFactory(), new ConsoleHumanInterface(), System.out, System.err,
                Clock.systemDefaultZone(), Sleeper.SYSTEM, CommandRunner.SYSTEM, selfCommand());
    }

    WeaveEnv withWeave(List<String> command) {
        return new WeaveEnv(models, human, out, err, clock, sleeper, commands, command, env, askVia, secrets);
    }

    /** {@code java -cp <this classpath> io.github.llm4j.loom.cli.WeaveCLI}: runs this weave again later. */
    static List<String> selfCommand() {
        String java = ProcessHandle.current().info().command().orElse("java");
        return List.of(java, "-cp", System.getProperty("java.class.path"), WeaveCLI.class.getName());
    }
}
