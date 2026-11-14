package io.github.llm4j.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.Isolation;
import io.github.llm4j.agent.tool.ToolProfile;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * {@code tool Python { use: sandbox workspace: Data isolation: container image: "python:3.12-slim"
 * allow: "python3" }}: runs an allow-listed program on the files of a {@code workspace} tool,
 * inside the isolation the script declares (phase 35, D5 to D8). There is no default isolation, a
 * request for {@code container} never falls back to a weaker level, and a backend that cannot
 * enforce a setting refuses it at load. {@code shell} stays the tool for a few host commands; this
 * is the only place an interpreter may run.
 */
public final class SandboxKind extends GenericKind {

    /** How long a run may take when the script says nothing. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    /** The longest any script may give a run, or a served program. */
    public static final Duration TIMEOUT_CEILING = Duration.ofHours(1);

    static final long DEFAULT_MAX_OUTPUT = 64 * 1024;
    static final long MAX_OUTPUT_CEILING = 4L * 1024 * 1024;
    static final int MAX_SERVED_CEILING = 4;
    static final String ENV = "env.";

    /** Programs that run other programs: allowed here, and named in the audit note. */
    private static final Set<String> INTERPRETERS =
            Set.of("sh", "bash", "zsh", "dash", "ash", "busybox", "node", "deno", "bun", "python", "python3", "ruby", "perl", "php", "java", "jshell", "env", "xargs", "npx");

    private final Function<Settings, SandboxBackend> backends;
    private final boolean gitWritable;

    /** Backends as D8 names them: {@code process}, {@code container}; a {@code vm} needs one of the consumer's. */
    public SandboxKind() {
        this(SandboxKind::standardBackend);
    }

    /** With a chosen way to make backends: a test's {@link FakeSandbox}, or a consumer's own. */
    public SandboxKind(Function<Settings, SandboxBackend> backends) {
        this(backends, false);
    }

    private SandboxKind(Function<Settings, SandboxBackend> backends, boolean gitWritable) {
        this.backends = backends;
        this.gitWritable = gitWritable;
    }

    /**
     * The sandbox the git tool runs git in: the only one that may write a repository's
     * {@code .git} folder. Every other sandbox mounts it read only, so a program there cannot plant
     * a filter driver or a hook that git would run later. No script option turns this on.
     */
    public static SandboxKind forGit(Function<Settings, SandboxBackend> backends) {
        return new SandboxKind(backends, true);
    }

    /** {@link #forGit(Function)} with the standard backends. */
    public static SandboxKind forGit() {
        return forGit(SandboxKind::standardBackend);
    }

    /** What a backend is made from: the declared isolation and the container settings. */
    public record Settings(Isolation isolation, String runtime, String image, boolean pull) {}

    @Override
    public String name() {
        return "sandbox";
    }

    @Override
    public Set<String> required() {
        return Set.of("workspace", "isolation", "allow");
    }

    @Override
    public Set<String> optional() {
        return Set.of(
                "network", "hosts", "image", "runtime", "pull", "timeout", "max_output", "memory", "pids", "disk",
                "unattended", "serve_port", "max_served");
    }

    @Override
    public Set<String> prefixes() {
        return Set.of(ENV, "workspace.");
    }

    @Override
    public Set<String> secrets() {
        return Set.of();
    }

    @Override
    public Set<String> resolvedPerCall() {
        return Set.of(ENV);
    }

    @Override
    public Map<String, String> references() {
        return Map.of("workspace", "workspace");
    }

    /** Built as the run starts: its runtime is asked for and a crashed run's leftovers stopped. */
    @Override
    public boolean buildsAtRunStart() {
        return true;
    }

    /** A sandbox runs programs: its agent must have it approved, or it says it runs unattended. */
    @Override
    public String agentProblem(Map<String, String> options, String toolName, String agentName, boolean approved) {
        if (approved || "true".equals(options.get("unattended"))) return null;
        return "tool "
                + toolName
                + " runs programs: add it to "
                + agentName
                + "'s approve: list, or set unattended: true on the tool if nobody should be asked";
    }

    static Isolation isolation(Options o) {
        if (!o.has("isolation"))
            throw new OptionException("isolation: is required (process, container or vm); there is no default");
        return Isolation.valueOf(o.choice("isolation", null, "process", "container", "vm").toUpperCase(Locale.ROOT));
    }

    static SandboxRequest.SandboxNetwork network(Options o) {
        String mode = o.choice("network", "none", "none", "allow", "open");
        if (mode.equals("allow")) {
            String problem = EgressProxy.Hosts.problem(o.get("hosts"));
            if (problem != null) throw new OptionException(problem);
            return new SandboxRequest.SandboxNetwork(SandboxRequest.SandboxNetwork.Mode.ALLOW, EgressProxy.Hosts.parse(o.get("hosts")));
        }
        if (o.has("hosts")) throw new OptionException("hosts: goes with network: allow");
        return new SandboxRequest.SandboxNetwork(
                mode.equals("open") ? SandboxRequest.SandboxNetwork.Mode.OPEN : SandboxRequest.SandboxNetwork.Mode.NONE, null);
    }

    static List<String> allow(Options o) {
        List<String> allow = o.list("allow");
        if (allow.isEmpty()) throw new OptionException("allow: needs at least one program name, e.g. \"python3\"");
        for (String name : allow)
            if (name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isWhitespace))
                throw new OptionException("allow: " + name + " must be a program name, not a path or a command line");
        return allow;
    }

    /** Settings checked together: everything a backend cannot enforce is refused here. */
    record Checked(
            Isolation isolation,
            List<String> allow,
            SandboxRequest.SandboxNetwork network,
            SandboxRequest.SandboxLimits limits,
            Path workspace,
            Path store,
            List<String> protect,
            Map<String, String> env,
            Integer servePort,
            int maxServed,
            SandboxBackend backend) {}

    Checked checked(Options o, Path baseDir, boolean probe) {
        Isolation isolation = isolation(o);
        List<String> allow = allow(o);
        SandboxRequest.SandboxNetwork network = network(o);
        Duration timeout = o.duration("timeout", DEFAULT_TIMEOUT, TIMEOUT_CEILING);
        Long memory = o.has("memory") ? o.size("memory", 0, Long.MAX_VALUE / 4) : null;
        Integer pids = o.has("pids") ? o.integer("pids", 0, 1, 100_000) : null;
        Long disk = o.has("disk") ? o.size("disk", 0, Long.MAX_VALUE / 4) : null;
        SandboxRequest.SandboxLimits limits =
                new SandboxRequest.SandboxLimits(timeout, o.size("max_output", DEFAULT_MAX_OUTPUT, MAX_OUTPUT_CEILING), memory, pids, disk);
        String image = o.get("image");
        if (isolation == Isolation.CONTAINER && image == null)
            throw new OptionException("image: is required with isolation: container, e.g. image: \"python:3.12-slim\"");
        if (isolation != Isolation.CONTAINER && (image != null || o.has("runtime") || o.has("pull")))
            throw new OptionException("image:, runtime: and pull: go with isolation: container");
        String runtime = o.has("runtime") ? o.choice("runtime", "docker", "docker", "podman") : null;
        if (!o.has("workspace.root") && !o.has("workspace"))
            throw new OptionException("workspace: names the workspace tool whose files the program works on");
        Path workspace = RootedFiles.resolveRoot(o.string("workspace.root", "."), baseDir);
        String wsName = o.string("workspace", "workspace");
        Path store = store(o, "", baseDir);
        List<String> protect = o.list("workspace.protect");
        Map<String, String> env = new LinkedHashMap<>();
        o.values()
                .forEach(
                        (k, v) -> {
                            if (k.startsWith(ENV)) {
                                String name = k.substring(ENV.length());
                                if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
                                    throw new OptionException(k + ": " + name + " is not a variable name");
                                env.put(name, v);
                            }
                        });
        Integer servePort = o.has("serve_port") ? o.integer("serve_port", 0, 1, 65535) : null;
        int maxServed = o.integer("max_served", 1, 1, MAX_SERVED_CEILING);

        SandboxBackend backend = backends.apply(new Settings(isolation, runtime, image, o.bool("pull", false)));
        if (backend.isolation().compareTo(isolation) < 0)
            throw new OptionException(
                    "isolation: the backend applies " + level(backend.isolation()) + ", below the " + level(isolation) + " the script asks for");
        Set<SandboxBackend.Limit> enforced = backend.enforces();
        String weak = level(backend.isolation()) + " isolation";
        if (network.mode() == SandboxRequest.SandboxNetwork.Mode.NONE && !enforced.contains(SandboxBackend.Limit.NETWORK_NONE))
            throw new OptionException(
                    "network: " + weak + " cannot keep a program off the network; write network: open to accept that, or use isolation: container");
        if (network.mode() == SandboxRequest.SandboxNetwork.Mode.ALLOW && !enforced.contains(SandboxBackend.Limit.NETWORK_ALLOW))
            throw new OptionException("network: allow needs a backend that can hold a program to a list of hosts; " + weak + " cannot");
        if (memory != null && !enforced.contains(SandboxBackend.Limit.MEMORY))
            throw new OptionException("memory: " + weak + " cannot enforce it");
        if (pids != null && !enforced.contains(SandboxBackend.Limit.PIDS))
            throw new OptionException("pids: " + weak + " cannot enforce it");
        if (disk != null && !enforced.contains(SandboxBackend.Limit.DISK))
            throw new OptionException("disk: " + weak + " cannot enforce it");
        if (!protect.isEmpty() && !enforced.contains(SandboxBackend.Limit.READ_ONLY_PATHS))
            throw new OptionException(
                    "workspace: " + wsName + " protects " + String.join(", ", protect) + ", which " + weak + " cannot keep read only; use isolation: container");
        if (servePort != null && !backend.canServe())
            throw new OptionException("serve_port: this backend cannot keep a program running");
        // weave check looks at the script, not the machine it runs on: the runtime is asked when the
        // tool is built, as the run starts, before any step (R12)
        String missing = probe ? backend.probe() : null;
        if (missing != null) throw new OptionException("isolation: " + missing);
        return new Checked(isolation, allow, network, limits, workspace, store, protect, env, servePort, maxServed, backend);
    }

    /**
     * The store of the workspace a sandbox works on, where its leases live: the workspace tool's
     * {@code store:}, or its default. {@code prefix} is "" for the sandbox's own options and
     * {@code reaches.} for a tool that names the sandbox.
     */
    static Path store(Options o, String prefix, Path baseDir) {
        Path base = baseDir.toAbsolutePath().normalize();
        String given = o.get(prefix + "workspace.store");
        return given != null
                ? base.resolve(given).normalize()
                : base.resolve(".loom-workspace").resolve(o.string(prefix + "workspace", "workspace"));
    }

    /**
     * The one-port grant of D7 for a tool that names a sandbox with {@code reaches:} (an
     * {@code http} or {@code browser} tool): true for a port on which that sandbox serves a program
     * now, read from its leases on every call. An unreadable lease grants nothing.
     *
     * @param options the reaching tool's options, with the sandbox's under {@code reaches.}
     */
    public static java.util.function.IntPredicate servedPorts(
            Map<String, String> options, Path baseDir, java.time.Clock clock) {
        Options o = new Options(options);
        String owner = o.require("reaches");
        Leases leases = new Leases(store(o, "reaches.", baseDir), clock);
        return port -> {
            try {
                return leases.of(owner).stream().anyMatch(l -> l.port() == port && leases.live(l));
            } catch (java.io.IOException e) {
                return false;
            }
        };
    }

    static String level(Isolation i) {
        return i.name().toLowerCase(Locale.ROOT);
    }

    /** D8: process; container with the named runtime (docker, else podman, from PATH); vm from a consumer. */
    static SandboxBackend standardBackend(Settings s) {
        return switch (s.isolation()) {
            case PROCESS -> new ProcessSandbox();
            case CONTAINER -> new ContainerSandbox(s.runtime() != null ? s.runtime() : runtimeOnPath(), s.image(), s.pull());
            case VM -> java.util.ServiceLoader.load(SandboxBackend.class).stream()
                    .map(java.util.ServiceLoader.Provider::get)
                    .filter(b -> b.isolation() == Isolation.VM)
                    .findFirst()
                    .orElseThrow(
                            () -> new OptionException(
                                    "isolation: vm needs a backend of your own (a SandboxBackend listed in META-INF/services); llm4j ships none"));
            case NONE -> throw new OptionException("isolation: none is not a sandbox; use the shell tool for host commands");
        };
    }

    /** {@code docker}, else {@code podman}, as found on the PATH; {@code docker} when neither is. */
    static String runtimeOnPath() {
        for (String runtime : List.of("docker", "podman")) {
            try {
                ProcessSandbox.onPath(runtime);
                return runtime;
            } catch (ToolRefusal notThere) {
                // try the next
            }
        }
        return "docker";
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        checked(o, baseDir, false);
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        return new SandboxTool(name, checked(o, baseDir, true), context, gitWritable);
    }

    /** Reaches the workspace; with a network it reads from and sends to the outside; it changes things. */
    @Override
    public ToolProfile profile(Map<String, String> options) {
        String network = options.getOrDefault("network", "none");
        boolean online = !network.equals("none");
        String isolation = options.getOrDefault("isolation", "none");
        Set<String> interpreters = new TreeSet<>();
        for (String p : options.getOrDefault("allow", "").split(","))
            if (INTERPRETERS.contains(p.strip().toLowerCase(Locale.ROOT))) interpreters.add(p.strip());
        Set<String> secrets = new TreeSet<>();
        options.forEach((k, v) -> {
            if (k.startsWith(ENV)) secrets.add(k.substring(ENV.length()) + " from " + v);
        });
        String note =
                "runs "
                        + options.get("allow")
                        + " at isolation "
                        + isolation
                        + ", network "
                        + (network.equals("allow") ? "allow (" + options.get("hosts") + ")" : network)
                        + (interpreters.isEmpty() ? "" : "; " + String.join(", ", interpreters) + " can run any command inside the " + (isolation.equals("container") ? "container" : "sandbox"))
                        + (secrets.isEmpty() ? "" : "; environment: " + String.join(", ", secrets));
        Isolation level;
        try {
            level = Isolation.valueOf(isolation.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            level = Isolation.NONE;
        }
        return ToolProfile.of(online, true, online, true, note).journaled(true).isolated(level);
    }

    /** What the program printed may be anything a model's code wrote. */
    @Override
    public Set<String> uncheckedResult(Map<String, String> options) {
        return Set.of("stdout", "stderr");
    }
}
