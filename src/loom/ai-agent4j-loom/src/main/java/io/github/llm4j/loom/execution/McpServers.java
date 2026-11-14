package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.ast.McpServerDef;
import io.github.llm4j.loom.security.McpEffects;
import io.github.llm4j.mcp.McpClient;
import io.github.llm4j.mcp.McpException;
import io.github.llm4j.mcp.McpToolAdapter;
import io.github.llm4j.mcp.StdioMcpTransport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The MCP servers a script declares, started once when the engine boots (phase 36a, D6 and D7). A
 * server's tools are listed then, so a server that cannot list them is a load error and not a log
 * line, and each agent that names the server gets its tools wrapped like any other tool.
 *
 * <p>A block with an {@code isolation:} line runs its program with only the environment it
 * declares, one argument per {@code args:} entry, and its error stream captured, redacted and
 * bounded. A block without one runs as it always did, with this process's whole environment.
 */
final class McpServers implements AutoCloseable {

    private static final Logger log = Logger.getLogger(McpServers.class.getName());

    /** How many times a crashed server is started again before the call fails for good. */
    static final int MAX_RESTARTS = 2;

    private static final int EXCERPT_CHARS = 2000;
    private static final int MIN_SECRET_CHARS = 4;

    private record Booted(
            McpServerDef def,
            McpClient client,
            StdioMcpTransport[] latest,
            List<Map<String, Object>> tools) {}

    private final Map<String, Booted> servers = new LinkedHashMap<>();

    /**
     * Starts every declared server and lists its tools.
     *
     * @param credentials resolves {@code env.NAME} and {@code secret.NAME} for a server's variables
     * @throws LoomLoadException when a server cannot start or cannot list its tools
     */
    void boot(
            List<McpServerDef> declared, Function<String, String> credentials) {
        for (McpServerDef def : declared) {
            if (def.getCmd() == null || def.getCmd().isBlank()) {
                log.warning("MCP server '" + def.getName() + "' has no cmd — skipping.");
                continue;
            }
            try {
                servers.put(def.getName(), start(def, credentials));
                log.info("MCP server initialised: " + def.getName());
            } catch (Exception e) {
                close();
                throw new LoomLoadException(
                        List.of(
                                new ScriptValidator.Problem(
                                        def.getLine(),
                                        "mcp " + def.getName(),
                                        problem(def, e),
                                        ScriptValidator.Severity.ERROR)));
            }
        }
    }

    private static String problem(McpServerDef def, Exception e) {
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return e instanceof McpException
                ? "could not list the tool list of `" + def.getCmd() + "`: " + reason
                : "failed to start `" + def.getCmd() + "`: " + reason;
    }

    private Booted start(McpServerDef def, Function<String, String> credentials) throws Exception {
        Map<String, String> env = McpChecks.environmentOf(def, credentials);
        StdioMcpTransport[] latest = new StdioMcpTransport[1];
        Supplier<io.github.llm4j.mcp.McpTransport> factory = transportFactory(def, env, latest);
        Duration timeout = def.getTimeout() == null ? McpClient.DEFAULT_TIMEOUT : def.getTimeout();
        McpClient client = McpClient.restarting(factory, MAX_RESTARTS, timeout);
        try {
            client.initialize();
            return new Booted(def, client, latest, client.listTools());
        } catch (Exception e) {
            client.close();
            throw e;
        }
    }

    /** What starts the program: the legacy way, or with a scrubbed environment. */
    private static Supplier<io.github.llm4j.mcp.McpTransport> transportFactory(
            McpServerDef def, Map<String, String> env, StdioMcpTransport[] latest) {
        List<String> command = new ArrayList<>();
        if (def.getIsolation() == null) command.addAll(List.of(def.getCmd().split("\\s+")));
        else command.add(def.getCmd().strip());
        command.addAll(def.getArgs());
        if (def.getIsolation() == null)
            return () -> latest[0] = new StdioMcpTransport(command, env.isEmpty() ? null : env);
        List<String> secrets = new ArrayList<>();
        env.forEach(
                (name, value) -> {
                    if (value != null && value.length() >= MIN_SECRET_CHARS) secrets.add(value);
                });
        return () ->
                latest[0] =
                        StdioMcpTransport.scrubbed(
                                command,
                                env,
                                Set.copyOf(def.getEnvInherit()),
                                text -> mask(text, secrets));
    }

    private static String mask(String text, List<String> secrets) {
        String out = text;
        for (String secret : secrets) out = out.replace(secret, "***");
        return out;
    }

    /** Whether a server of this name was started. */
    boolean has(String server) {
        return servers.containsKey(server);
    }

    /** The tools a server listed, as it gave them. */
    List<Map<String, Object>> listed(String server) {
        Booted booted = servers.get(server);
        return booted == null ? List.of() : List.copyOf(booted.tools());
    }

    /** The names of the tools a server lists. */
    List<String> toolNames(String server) {
        Booted booted = servers.get(server);
        if (booted == null) return List.of();
        List<String> names = new ArrayList<>();
        booted.tools().forEach(t -> names.add(String.valueOf(t.get("name"))));
        return names;
    }

    /** The server's tools for one agent, before the wrappers every tool gets. */
    List<Tool> toolsOf(String server) {
        Booted booted = servers.get(server);
        if (booted == null) return List.of();
        List<Tool> out = new ArrayList<>();
        for (Map<String, Object> listed : booted.tools())
            out.add(new ServerTool(booted, listed));
        return out;
    }

    /**
     * The names of the tools of these servers the developer declared to read or touch nothing
     * ({@code effects:}); every other tool is assumed to change things.
     */
    Set<String> notChanging(List<String> serverNames) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String name : serverNames) {
            Booted booted = servers.get(name);
            if (booted == null) continue;
            for (Map<String, Object> listed : booted.tools()) {
                String tool = String.valueOf(listed.get("name"));
                if (McpEffects.resolve(booted.def(), tool, listed).effect() != TaskEffect.CHANGES)
                    out.add(tool);
            }
        }
        return out;
    }

    @Override
    public void close() {
        for (Map.Entry<String, Booted> e : servers.entrySet()) {
            try {
                e.getValue().client().close();
            } catch (Exception failure) {
                log.warning("MCP server " + e.getKey() + " did not stop cleanly: " + failure.getMessage());
            }
        }
        servers.clear();
    }

    /**
     * A tool of a server. A failure carries the end of the server's error stream (already redacted
     * and bounded) so a trace or {@code weave why} shows what the server said.
     */
    static final class ServerTool extends McpToolAdapter {
        private final Booted booted;
        private final TaskEffect effect;

        ServerTool(Booted booted, Map<String, Object> listed) {
            super(booted.client(), listed);
            this.booted = booted;
            this.effect =
                    McpEffects.resolve(booted.def(), String.valueOf(listed.get("name")), listed)
                            .effect();
        }

        /** What the developer declared for this tool, or {@code changes} when nobody did. */
        TaskEffect effect() {
            return effect;
        }

        @Override
        public String execute(Map<String, Object> args) throws Exception {
            try {
                return super.execute(args);
            } catch (McpException failure) {
                StdioMcpTransport transport = booted.latest()[0];
                String said = transport == null ? "" : transport.stderrTail().strip();
                if (said.isEmpty()) throw failure;
                String excerpt =
                        said.length() > EXCERPT_CHARS
                                ? "…" + said.substring(said.length() - EXCERPT_CHARS)
                                : said;
                throw new McpException(
                        failure.getMessage() + "\nthe server's error stream ended with:\n" + excerpt,
                        failure);
            }
        }
    }
}
