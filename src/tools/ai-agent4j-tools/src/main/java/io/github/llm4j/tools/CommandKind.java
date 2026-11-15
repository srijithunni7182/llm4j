package io.github.llm4j.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.Isolation;
import io.github.llm4j.agent.tool.ToolProfile;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code tool Resize { use: command run: "convert {input} -resize {percent}% {output}" param.input:
 * "path in" … isolation: container image: "…" workspace: Data }} (phase 36c, D9): one declared tool is
 * one command, with a template the author wrote and typed parameters the agent fills. Injection through
 * an argument is impossible by construction (a value is one argument), the audit knows exactly what
 * runs, and the program is run through the sandbox backend of phase 35, so isolation is required and a
 * backend that cannot hold what the script asks for is refused.
 */
public final class CommandKind extends GenericKind {

    private static final String PARAM = "param.";
    private static final int ERROR_EXCERPT = 300;

    private final Function<SandboxKind.Settings, SandboxBackend> backends;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The standard backends: a child process, or a container of the runtime on the PATH. */
    public CommandKind() {
        this(SandboxKind::standardBackend);
    }

    /** With backends of your own (a fake, for tests). */
    public CommandKind(Function<SandboxKind.Settings, SandboxBackend> backends) {
        this.backends = backends;
    }

    @Override
    public String name() {
        return "command";
    }

    @Override
    public Set<String> required() {
        return Set.of("run", "isolation");
    }

    @Override
    public Set<String> optional() {
        return Set.of("image", "runtime", "pull", "workspace", "network", "hosts", "timeout", "max_output", "output", "ok_exit", "on_unknown");
    }

    @Override
    public Set<String> prefixes() {
        return Set.of(PARAM, "workspace.");
    }

    @Override
    public Map<String, String> references() {
        return Map.of("workspace", "workspace");
    }

    @Override
    public boolean buildsAtRunStart() {
        return true;
    }

    /** A program run: its agent must have it approved, as for a sandbox. */
    @Override
    public String agentProblem(Map<String, String> options, String toolName, String agentName, boolean approved) {
        return approved
                ? null
                : "tool " + toolName + " runs a program: add it to " + agentName + "'s approve: list";
    }

    @Override
    public ToolProfile profile(Map<String, String> options) {
        String level = options.getOrDefault("isolation", "none");
        boolean online = !options.getOrDefault("network", "none").equals("none");
        String program = options.getOrDefault("run", "").split("\\s+")[0];
        Isolation isolation = switch (level) {
            case "container" -> Isolation.CONTAINER;
            case "vm" -> Isolation.VM;
            case "process" -> Isolation.PROCESS;
            default -> Isolation.NONE;
        };
        return ToolProfile.of(true, true, online, true, "runs " + program + " at isolation " + level + (online ? ", with network" : ""))
                .journaled(true)
                .isolated(isolation);
    }

    private record Checked(
            CommandTemplate template,
            Isolation isolation,
            SandboxRequest.SandboxNetwork network,
            SandboxRequest.SandboxLimits limits,
            Path workspace,
            List<Integer> okExit,
            String output,
            SandboxBackend backend) {}

    private Checked checked(Options o, Path baseDir, boolean probe) {
        Isolation isolation = SandboxKind.isolation(o);
        if (isolation != Isolation.PROCESS && isolation != Isolation.CONTAINER && isolation != Isolation.VM)
            throw new OptionException("isolation: is required (process, container or vm); a command is run in a sandbox");
        Map<String, String> specs = new LinkedHashMap<>();
        o.values().forEach((k, v) -> {
            if (k.startsWith(PARAM)) specs.put(k.substring(PARAM.length()), v);
        });
        CommandTemplate template = CommandTemplate.parse(o.require("run"), specs);
        SandboxRequest.SandboxNetwork network = SandboxKind.network(o);
        Duration timeout = o.duration("timeout", SandboxKind.DEFAULT_TIMEOUT, SandboxKind.TIMEOUT_CEILING);
        SandboxRequest.SandboxLimits limits = new SandboxRequest.SandboxLimits(timeout, o.size("max_output", 64 * 1024, 4L * 1024 * 1024), null, null, null);
        String image = o.get("image");
        if (isolation == Isolation.CONTAINER && (image == null || image.isBlank()))
            throw new OptionException("image: is required with isolation: container");
        if (isolation != Isolation.CONTAINER && (image != null || o.has("runtime") || o.has("pull")))
            throw new OptionException("image:, runtime: and pull: go with isolation: container");
        if (!o.has("workspace.root") && !o.has("workspace"))
            throw new OptionException("workspace: names the workspace tool whose files the command works on");
        Path workspace = RootedFiles.resolveRoot(o.string("workspace.root", "."), baseDir);
        List<Integer> ok = new ArrayList<>();
        for (String n : o.has("ok_exit") ? o.list("ok_exit") : List.of("0")) {
            try {
                ok.add(Integer.parseInt(n.strip()));
            } catch (NumberFormatException e) {
                throw new OptionException("ok_exit: " + n + " is not an exit code");
            }
        }
        String output = o.choice("output", "text", "text", "json", "lines");
        SandboxBackend backend = backends.apply(new SandboxKind.Settings(isolation, o.has("runtime") ? o.get("runtime") : null, image, o.bool("pull", false)));
        if (backend.isolation().compareTo(isolation) < 0)
            throw new OptionException("isolation: the backend applies " + SandboxKind.level(backend.isolation()) + ", below the " + SandboxKind.level(isolation) + " the script asks for");
        if (network.mode() == SandboxRequest.SandboxNetwork.Mode.NONE && !backend.enforces().contains(SandboxBackend.Limit.NETWORK_NONE))
            throw new OptionException("network: this isolation cannot keep a command off the network; write network: open to accept that, or use isolation: container");
        if (network.mode() == SandboxRequest.SandboxNetwork.Mode.ALLOW && !backend.enforces().contains(SandboxBackend.Limit.NETWORK_ALLOW))
            throw new OptionException("network: allow needs a backend that can hold a command to a list of hosts");
        String missing = probe ? backend.probe() : null;
        if (missing != null) throw new OptionException("isolation: " + missing);
        return new Checked(template, isolation, network, limits, workspace, ok, output, backend);
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        checked(o, baseDir, false);
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        Checked c = checked(o, baseDir, true);
        return new CommandTool(name, c.template(), c, context, o.get("description"));
    }

    private static final class CommandTool extends GenericTool {
        private final CommandTemplate template;
        private final Checked checked;
        private final String name;

        CommandTool(String name, CommandTemplate template, Checked checked, EffectContext context, String description) {
            super(name, "command", description != null ? description : "Runs " + template.program() + " with typed arguments: " + String.join(", ", template.params().keySet()) + ".", Redactor.NONE, context);
            this.name = name;
            this.template = template;
            this.checked = checked;
        }

        /** A program ran: always a journaled effect, whatever its parameters. */
        @Override
        public boolean isEffect(Map<String, Object> args) {
            return true;
        }

        @Override
        public io.github.llm4j.agent.tool.EffectPolicy policy() {
            return io.github.llm4j.agent.tool.EffectPolicy.DEFAULT;
        }

        @Override
        public String target(Map<String, Object> args) {
            return template.program();
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            template.params().forEach((n, p) -> properties.put(n, Map.of("type", p instanceof CommandTemplate.IntRange ? "integer" : p instanceof CommandTemplate.NumberRange ? "number" : p instanceof CommandTemplate.Flag ? "boolean" : "string")));
            return Map.of("type", "object", "properties", properties, "additionalProperties", false);
        }

        @Override
        protected String run(Map<String, Object> args, String idempotencyKey) {
            List<String> filled = template.fill(args, checked.workspace());
            SandboxRequest request = new SandboxRequest(template.program(), filled, checked.workspace(), "", List.of(), "", Map.of(), checked.limits(), checked.network(), name);
            SandboxResult result;
            try {
                result = checked.backend().run(request);
            } catch (IOException e) {
                throw new ToolRefusal("the command could not be run (" + e.getClass().getSimpleName() + ")");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolRefusal("interrupted while the command ran");
            }
            if (result.timedOut()) throw new UnknownOutcomeException("the command did not finish in time and may have done part of its work");
            if (!checked.okExit().contains(result.exit()))
                throw new ToolRefusal("the command ended with exit " + result.exit() + ": " + Limits.excerpt(result.stderr().isBlank() ? result.stdout() : result.stderr(), ERROR_EXCERPT));
            String out = result.stdout();
            switch (checked.output()) {
                case "json" -> {
                    try {
                        JSON.readTree(out);
                    } catch (IOException e) {
                        throw new ToolRefusal("the command's output is not JSON");
                    }
                }
                case "lines" -> {
                    try {
                        return JSON.writeValueAsString(out.lines().toList());
                    } catch (IOException e) {
                        throw new ToolRefusal("the command's output could not be read");
                    }
                }
                default -> { }
            }
            return result.truncated() ? out + Limits.marker(out.length(), out.length() + result.cutBytes()) : out;
        }
    }
}
