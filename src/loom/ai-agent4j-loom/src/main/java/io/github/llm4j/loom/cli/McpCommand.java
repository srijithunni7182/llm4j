package io.github.llm4j.loom.cli;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.McpServerDef;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.loom.execution.McpDiscovery;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.LoomLoader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave mcp list}: starts each MCP server a script declares, under the isolation its block
 * names, and lists its tools with what their annotations suggest. The annotations are the server's
 * own word and are untrusted, so nothing is acted on until a person approves it: {@code --write}
 * puts the proposed {@code effects:} and a {@code pin:} into the block after showing the change.
 */
@Command(
        name = "mcp",
        mixinStandardHelpOptions = true,
        description = "Looks at the MCP servers a script declares.",
        subcommands = {McpCommand.List.class})
final class McpCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(
            name = "list",
            mixinStandardHelpOptions = true,
            description =
                    "Starts each mcp server of a script as its block says, lists its tools with what their annotations suggest (the server's own, untrusted word) and the pin of the tool list. With --write, shows the change and, after you agree, writes the proposed effects: and the pin: into the block. Calls no tool.")
    static final class List implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<script>", description = "The script.")
        File script;

        @Option(names = "--server", paramLabel = "<name>", description = "Only this mcp block.")
        String server;

        @Option(
                names = "--write",
                description =
                        "Write the proposed effects: (for tools the block does not describe) and the pin: into the script, after showing the change and asking.")
        boolean write;

        @Option(names = "--yes", description = "With --write, do not ask first (the change is still shown).")
        boolean yes;

        @CommandLine.Mixin private EnvFileOptions envFile = new EnvFileOptions();

        @CommandLine.Mixin private SecretOptions secrets = new SecretOptions();

        @Override
        public Integer call() {
            WeaveEnv env = envFile.apply(WeaveEnv.system(), script.toPath());
            if (env == null) return 2;
            env = secrets.apply(env, Prompts.console());
            return env == null ? 2 : list(this, env);
        }

        static int list(List c, WeaveEnv env) {
            LoomScript loaded;
            try {
                loaded = new LoomLoader().load(c.script.getAbsolutePath());
            } catch (Exception e) {
                env.err().println("Error: " + c.script + " could not be read: " + e.getMessage());
                return 2;
            }
            java.util.List<McpServerDef> chosen = new java.util.ArrayList<>();
            for (McpServerDef def : loaded.getMcpServers())
                if (c.server == null || c.server.equals(def.getName())) chosen.add(def);
            if (chosen.isEmpty()) {
                env.err()
                        .println(
                                c.server == null
                                        ? "Error: " + c.script + " declares no mcp server."
                                        : "Error: " + c.script + " has no mcp "
                                                + c.server
                                                + " (it has "
                                                + loaded.getMcpServers().stream().map(McpServerDef::getName).toList()
                                                + ").");
                return 2;
            }
            Function<String, String> credentials = credentials(env);
            int worst = 0;
            for (McpServerDef def : chosen) {
                McpDiscovery.Result found;
                try {
                    found = McpDiscovery.discover(def, credentials);
                } catch (LoomLoadException e) {
                    env.err().println("Error: mcp " + def.getName() + ": " + describe(e));
                    worst = 1;
                    continue;
                }
                show(found, env);
                if (c.write) worst = Math.max(worst, write(c, found, env));
            }
            return worst;
        }

        private static String describe(LoomLoadException e) {
            return e.problems().stream()
                    .map(ScriptValidator.Problem::message)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse(e.getMessage());
        }

        /** What {@code env.NAME} and {@code secret.NAME} resolve to, as a run resolves them. */
        private static Function<String, String> credentials(WeaveEnv env) {
            return key -> {
                var store = env.secrets();
                if (key.startsWith("secret:")) {
                    String name = key.substring("secret:".length());
                    return store != null && store.contains(name) ? store.resolve(name) : null;
                }
                if (store != null
                        && io.github.llm4j.secret.SecretNames.isValid(key)
                        && store.contains(key)) return store.resolve(key);
                return env.env().apply(key);
            };
        }

        private static void show(McpDiscovery.Result r, WeaveEnv env) {
            var out = env.out();
            out.println(
                    "mcp "
                            + r.server()
                            + "  ("
                            + (r.isolation() == null ? "no isolation: line" : "isolation: " + r.isolation())
                            + ")  starts "
                            + r.command());
            out.println(
                    "The annotations below are the server's own word and are untrusted: nothing is acted on"
                            + " until you write it into the script.");
            out.println();
            out.println(String.format("  %-24s %-20s %-10s %s", "tool", "effect", "on_unknown", "the server says"));
            for (McpDiscovery.Tool t : r.tools()) {
                String effect =
                        t.declared() != null
                                ? name(t.declared()) + " (declared)"
                                : name(t.proposal().effect()) + " (proposed)";
                String says = annotationText(t) + " - " + t.proposal().why();
                out.println(
                        String.format(
                                "  %-24s %-20s %-10s %s",
                                t.name(),
                                effect,
                                t.declared() != null || t.proposal().onUnknown() == null ? "-" : t.proposal().onUnknown(),
                                says));
            }
            out.println();
            out.println("pin: " + r.pin() + "  " + pinState(r));
            out.println();
        }

        private static String annotationText(McpDiscovery.Tool t) {
            if (t.annotations().isEmpty()) return "no annotations";
            java.util.List<String> parts = new java.util.ArrayList<>();
            t.annotations().forEach((k, v) -> parts.add(k + "=" + v));
            return String.join(", ", parts);
        }

        private static String pinState(McpDiscovery.Result r) {
            if (r.declaredPin() == null) return "(the block has no pin)";
            return r.declaredPin().equals(r.pin())
                    ? "(matches the pin in the block)"
                    : "(differs from the pin in the block, " + r.declaredPin() + ": the server's tools changed)";
        }

        private static String name(TaskEffect effect) {
            return effect.name().toLowerCase(Locale.ROOT);
        }

        private static int write(List c, McpDiscovery.Result r, WeaveEnv env) {
            Map<String, TaskEffect> proposed = new LinkedHashMap<>();
            for (McpDiscovery.Tool t : r.tools())
                if (t.declared() == null) proposed.put(t.name(), t.proposal().effect());
            String before;
            String after;
            try {
                before = Files.readString(c.script.toPath());
                after = McpScriptEditor.apply(before, r.server(), proposed, r.pin());
            } catch (IOException | IllegalArgumentException e) {
                env.err().println("Error: " + e.getMessage());
                return 1;
            }
            if (after.equals(before)) {
                env.out().println("Nothing to change in mcp " + r.server() + ".");
                return 0;
            }
            env.out().println("The change to " + c.script.getName() + ":");
            env.out().print(McpScriptEditor.diff(before, after));
            if (!c.yes) {
                String answer = env.human().promptHuman("Write this change? (yes/no)");
                if (answer == null || !answer.strip().toLowerCase(Locale.ROOT).matches("y|yes")) {
                    env.out().println("Not written.");
                    return 0;
                }
            }
            try {
                Files.writeString(c.script.toPath(), after);
            } catch (IOException e) {
                env.err().println("Error: could not write " + c.script + ": " + e.getMessage());
                return 1;
            }
            env.out().println("Written.");
            return 0;
        }
    }
}
