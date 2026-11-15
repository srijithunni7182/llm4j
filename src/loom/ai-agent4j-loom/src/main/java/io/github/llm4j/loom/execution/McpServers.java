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
    private boolean enforcePins = true;

    /** Whether a block's {@code pin:} that no longer matches stops the start (the default); a listing turns it off to show the drift. */
    void enforcePins(boolean enforce) {
        this.enforcePins = enforce;
    }

    private void checkPin(McpServerDef def, List<Map<String, Object>> tools) {
        if (!enforcePins || def.getPin() == null) return;
        String now = io.github.llm4j.mcp.McpPin.of(tools);
        if (!def.getPin().equals(now))
            throw new IllegalStateException(
                    "the tools of this server are not the ones pinned (pin: " + def.getPin() + ", now " + now
                            + "): a tool was added, removed or changed since you reviewed it. Run weave mcp list to see the tools, and weave mcp list --write to pin the new list once you accept it");
    }
    private Function<String, io.github.llm4j.secret.CredentialSource> invoker = name -> null;
    private java.nio.file.Path baseDir = java.nio.file.Path.of("").toAbsolutePath();

    /** The directory a container's {@code workspace:} is relative to: the script's. */
    void baseDir(java.nio.file.Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * Starts every declared server and lists its tools.
     *
     * @param credentials resolves {@code env.NAME} and {@code secret.NAME} for a server's variables
     * @throws LoomLoadException when a server cannot start or cannot list its tools
     */
    void boot(List<McpServerDef> declared, Function<String, String> credentials) {
        boot(declared, credentials, name -> null);
    }

    /**
     * As above; {@code invoker} gives the credential of whoever runs the workflow for a remote
     * server's {@code auth: invoker.NAME}.
     */
    void boot(
            List<McpServerDef> declared,
            Function<String, String> credentials,
            Function<String, io.github.llm4j.secret.CredentialSource> invoker) {
        this.invoker = invoker;
        for (McpServerDef def : declared) {
            if (!def.isRemote() && (def.getCmd() == null || def.getCmd().isBlank())) {
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
        if (def.isRemote()) return startRemote(def, credentials);
        Map<String, String> env = McpChecks.environmentOf(def, credentials);
        StdioMcpTransport[] latest = new StdioMcpTransport[1];
        Supplier<io.github.llm4j.mcp.McpTransport> factory = transportFactory(baseDir, def, env, latest);
        Duration timeout = def.getTimeout() == null ? McpClient.DEFAULT_TIMEOUT : def.getTimeout();
        McpClient client = McpClient.restarting(factory, MAX_RESTARTS, timeout);
        try {
            client.initialize();
            List<Map<String, Object>> listed = client.listTools();
            checkPin(def, listed);
            return new Booted(def, client, latest, listed);
        } catch (Exception e) {
            client.close();
            throw e;
        }
    }

    /**
     * A remote server: one transport for this executor (so for one run at a time). The tools are
     * listed with {@code list_auth} (or a static {@code auth}); calls ask {@code auth} on every request,
     * which for {@code invoker.NAME} is the credential of the run that makes the call.
     */
    private Booted startRemote(McpServerDef def, Function<String, String> credentials) throws Exception {
        Duration timeout = def.getTimeout() == null ? McpClient.DEFAULT_TIMEOUT : def.getTimeout();
        java.util.concurrent.atomic.AtomicBoolean listing = new java.util.concurrent.atomic.AtomicBoolean(true);
        io.github.llm4j.secret.CredentialSource forListing =
                fixed(def.getListAuth() != null ? def.getListAuth() : def.getAuth() != null && !def.getAuth().fromInvoker() ? def.getAuth() : null, credentials);
        io.github.llm4j.secret.CredentialSource forCalls =
                def.getAuth() == null ? null : def.getAuth().fromInvoker() ? invoker.apply(def.getAuth().value()) : fixed(def.getAuth(), credentials);
        io.github.llm4j.secret.CredentialSource token =
                new io.github.llm4j.secret.CredentialSource() {
                    @Override
                    public String get() {
                        io.github.llm4j.secret.CredentialSource from = listing.get() ? forListing : forCalls;
                        return from == null ? null : from.get();
                    }

                    @Override
                    public void rejected() {
                        io.github.llm4j.secret.CredentialSource from = listing.get() ? forListing : forCalls;
                        if (from != null) from.rejected();
                    }
                };
        HttpMcpPoster poster = new HttpMcpPoster(def.getUrl(), timeout, io.github.llm4j.agent.tool.EffectContext.noop());
        // a server with no auth: and no list_auth: is reached with no credential at all, not with a source that has none
        boolean noCredential = def.getAuth() == null && def.getListAuth() == null;
        McpClient client = new McpClient(new io.github.llm4j.mcp.McpHttpTransport(def.getUrl(), poster, noCredential ? null : token), timeout);
        try {
            client.initialize();
            List<Map<String, Object>> tools = client.listTools();
            checkPin(def, tools);
            listing.set(false);
            return new Booted(def, client, new StdioMcpTransport[1], tools);
        } catch (Exception e) {
            client.close();
            throw e;
        }
    }

    private static io.github.llm4j.secret.CredentialSource fixed(
            io.github.llm4j.loom.ast.ToolDef.OptionValue v, Function<String, String> credentials) {
        if (v == null) return null;
        // looked up on every request, so a value the person has since rotated is the one sent after a rejection
        return () -> v.isReference() ? credentials.apply(v.lookupKey()) : v.value();
    }

    /** What starts the program: the legacy way, or with a scrubbed environment. */
    private static Supplier<io.github.llm4j.mcp.McpTransport> transportFactory(
            java.nio.file.Path baseDir,
            McpServerDef def, Map<String, String> env, StdioMcpTransport[] latest) {
        List<String> command = new ArrayList<>();
        if (def.getIsolation() == null) command.addAll(List.of(def.getCmd().split("\\s+")));
        else command.add(def.getCmd().strip());
        command.addAll(def.getArgs());
        if ("container".equals(def.getIsolation())) return containerFactory(baseDir, def, env, command, latest);
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

    /** Environment a container runtime itself needs to reach its daemon; none of it enters the container. */
    private static final Set<String> RUNTIME_ENV = Set.of("PATH", "HOME", "DOCKER_HOST", "DOCKER_CONFIG", "XDG_RUNTIME_DIR");

    /**
     * The server runs inside the container the sandbox builds (no capabilities, read-only file
     * system, the workspace the only mount, no network unless {@code network: open}); a secret is
     * named for the runtime, which reads its value from this process's environment.
     */
    private static Supplier<io.github.llm4j.mcp.McpTransport> containerFactory(
            java.nio.file.Path baseDir,
            McpServerDef def, Map<String, String> env, List<String> command, StdioMcpTransport[] latest) {
        java.nio.file.Path workspace = baseDir.resolve(def.getWorkspace() == null ? "." : def.getWorkspace()).normalize();
        try {
            // the real paths: a folder that is a link to somewhere else would mount that somewhere into the container
            if (!workspace.toRealPath().startsWith(baseDir.toRealPath()))
                throw new IllegalArgumentException("workspace: " + def.getWorkspace() + " is outside the script's directory");
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("workspace: " + def.getWorkspace() + " cannot be resolved: " + e.getMessage(), e);
        }
        var network =
                new io.github.llm4j.tools.SandboxRequest.SandboxNetwork(
                        "open".equals(def.getNetwork())
                                ? io.github.llm4j.tools.SandboxRequest.SandboxNetwork.Mode.OPEN
                                : io.github.llm4j.tools.SandboxRequest.SandboxNetwork.Mode.NONE,
                        null);
        var limits = new io.github.llm4j.tools.SandboxRequest.SandboxLimits(Duration.ofHours(1), 1, null, null, null);
        var request =
                new io.github.llm4j.tools.SandboxRequest(
                        command.get(0), command.subList(1, command.size()), workspace, "", List.of(), "", env, limits, network, "llm4j-mcp-" + def.getName());
        List<String> docker =
                new io.github.llm4j.tools.ContainerSandbox("docker", def.getImage(), false).serverCommand(request);
        List<String> secrets = new ArrayList<>();
        env.forEach((name, value) -> {
            if (value != null && value.length() >= MIN_SECRET_CHARS) secrets.add(value);
        });
        return () -> latest[0] = StdioMcpTransport.scrubbed(docker, env, RUNTIME_ENV, text -> mask(text, secrets));
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
        // a tool name two servers share is not changing only when every one of them says so: one server's
        // declared read must not vouch for another server's undeclared tool of the same name
        Set<String> notChanging = new java.util.LinkedHashSet<>();
        Set<String> changing = new java.util.LinkedHashSet<>();
        for (String name : serverNames) {
            Booted booted = servers.get(name);
            if (booted == null) continue;
            for (Map<String, Object> listed : booted.tools()) {
                String tool = String.valueOf(listed.get("name"));
                if (McpEffects.resolve(booted.def(), tool, listed).effect() != TaskEffect.CHANGES) notChanging.add(tool);
                else changing.add(tool);
            }
        }
        notChanging.removeAll(changing);
        return notChanging;
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
