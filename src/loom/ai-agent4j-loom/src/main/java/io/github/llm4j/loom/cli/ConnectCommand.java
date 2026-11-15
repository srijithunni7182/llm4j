package io.github.llm4j.loom.cli;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.McpServerDef;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.loom.execution.McpDiscovery;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.tools.openapi.ApiDescription;
import io.github.llm4j.tools.openapi.ApiOperation;
import io.github.llm4j.tools.openapi.OpenApiKind;
import io.github.llm4j.tools.openapi.SpecPin;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave connect}: the guided way to bring a system into a script (phase 36d, D10). {@code
 * openapi} and {@code mcp} are deterministic: they read the description or the server, propose each
 * operation's effect from what the system itself says, show the change and write it only when a person
 * approves. {@code command} and {@code http} are model-assisted and live in {@link ConnectDraft}.
 *
 * <p>Four rules hold for every form. A secret is never written: a declaration holds a reference, never
 * a value. A result that fails {@code weave check} is never written. A description, a help text and a
 * server's answer are untrusted input, and a model's classification is a proposal the person approves.
 * With {@code --refresh --check} nothing is ever written: it reports drift against the pins and exits
 * non-zero when there is any.
 */
@Command(
        name = "connect",
        mixinStandardHelpOptions = true,
        description = "Brings a system into a script: one tool per chosen operation, with the effect proposed and approved and a pin to notice drift.",
        subcommands = {ConnectCommand.OpenApi.class, ConnectCommand.Mcp.class, ConnectDraft.CommandForm.class, ConnectDraft.HttpForm.class})
final class ConnectCommand implements Callable<Integer> {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern REFERENCE = Pattern.compile("(env|secret|invoker)\\.[A-Za-z_][A-Za-z0-9_]*");

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    // ── openapi ───────────────────────────────────────────────────────────────────────────

    @Command(
            name = "openapi",
            mixinStandardHelpOptions = true,
            description =
                    "Reads an OpenAPI description, lists its operations with the effect each would get (from its HTTP method), and writes one tool declaration per chosen operation with effect:, on_unknown: and pin:. Without --write it only prints what it would write. With --refresh the argument is a script, and each pinned openapi tool is read again.")
    static final class OpenApi implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<spec|script>", description = "An OpenAPI description (a file or an https address); with --refresh, the script whose tools are read again.")
        String spec;

        @Option(names = "--operations", split = ",", paramLabel = "<ids>", description = "Only these operations, by operationId.")
        List<String> operations = new ArrayList<>();

        @Option(names = "--read-only", description = "Only the operations that read (GET, HEAD, OPTIONS).")
        boolean readOnly;

        @Option(names = "--write", paramLabel = "<script>", description = "Add the tools to this script, after showing the change and asking.")
        File write;

        @Option(names = "--yes", description = "The effects as proposed are approved: do not ask (the change is still shown).")
        boolean yes;

        @Option(names = "--prefix", paramLabel = "<name>", description = "The tools are named <name>_<operationId> (default: Api).")
        String prefix = "Api";

        @Option(names = "--auth", paramLabel = "<ref>", description = "Where the credential comes from, written as auth_value: env.NAME, secret.NAME or invoker.NAME. Never a value.")
        String auth;

        @Option(names = "--auth-header", paramLabel = "<header>", description = "The header the credential goes in (default: Authorization).")
        String authHeader = "Authorization";

        @Option(names = "--refresh", description = "Read each pinned openapi tool of the script again and report what changed.")
        boolean refresh;

        @Option(names = "--check", description = "With --refresh: change nothing, print the drift, and exit 1 when there is any (for CI).")
        boolean check;

        @Override
        public Integer call() {
            return run(this, WeaveEnv.system());
        }

        static int run(OpenApi c, WeaveEnv env) {
            if (c.check && !c.refresh) {
                env.err().println("Error: --check goes with --refresh.");
                return 2;
            }
            return c.refresh ? refresh(c, env) : connect(c, env);
        }

        private static int connect(OpenApi c, WeaveEnv env) {
            if (c.auth != null && !REFERENCE.matcher(c.auth).matches()) {
                env.err().println("Error: --auth is where the credential comes from (env.NAME, secret.NAME or invoker.NAME), never the credential itself.");
                return 2;
            }
            if (!IDENTIFIER.matcher(c.prefix).matches()) {
                env.err().println("Error: --prefix must be a name made of letters, digits and underscores.");
                return 2;
            }
            Path base = c.write == null ? Path.of("").toAbsolutePath() : c.write.toPath().toAbsolutePath().getParent();
            ApiDescription description;
            try {
                description = OpenApiKind.read(c.spec, base);
            } catch (IllegalArgumentException e) {
                env.err().println("Error: " + e.getMessage());
                return 2;
            }
            List<ApiOperation> chosen = choose(description, c, env);
            if (chosen == null) return 2;
            if (chosen.isEmpty()) {
                env.err().println("Error: no operation was chosen.");
                return 2;
            }
            String where = locationFor(c.spec, base);
            StringBuilder text = new StringBuilder();
            Set<String> names = new LinkedHashSet<>();
            Set<String> existing = c.write == null ? Set.of() : toolNames(c.write.toPath(), env);
            for (ApiOperation op : chosen) {
                String name = c.prefix + "_" + op.id().replaceAll("[^A-Za-z0-9_]", "_");
                if (existing.contains(name) || !names.add(name)) {
                    env.err().println("Error: the script already has a tool " + name + "; give --prefix, or choose fewer operations.");
                    return 2;
                }
                text.append(declaration(name, where, op, c)).append('\n');
            }
            env.out().println("Operations of " + description.title() + ", with the effect each tool would get (from its HTTP method, a proposal you approve):");
            for (ApiOperation op : chosen)
                env.out().println(String.format("  %-6s %-40s %-8s %s", op.method(), op.path(), effectOf(op), op.id()));
            if (c.write == null) {
                env.out().println();
                env.out().println("Nothing is written (give --write <script>). The declarations would be:");
                env.out().println();
                env.out().print(text);
                return 0;
            }
            try {
                String before = Files.readString(c.write.toPath());
                return ConnectEdit.apply(c.write.toPath().toAbsolutePath(), before, ConnectEdit.append(before, text.toString()), names, c.yes, env);
            } catch (IOException e) {
                env.err().println("Error: " + c.write + " could not be read: " + e.getMessage());
                return 2;
            }
        }

        private static List<ApiOperation> choose(ApiDescription description, OpenApi c, WeaveEnv env) {
            List<ApiOperation> all = description.operations();
            if (!c.operations.isEmpty()) {
                List<ApiOperation> picked = new ArrayList<>();
                for (String id : c.operations) {
                    var found = description.operation(id.strip());
                    if (found.isEmpty()) {
                        env.err().println("Error: the description has no operation " + id + " (it has " + all.stream().map(ApiOperation::id).toList() + ").");
                        return null;
                    }
                    picked.add(found.get());
                }
                return c.readOnly ? picked.stream().filter(ApiOperation::isRead).toList() : picked;
            }
            if (c.readOnly) return all.stream().filter(ApiOperation::isRead).toList();
            env.out().println("Operations in " + description.title() + ":");
            for (int i = 0; i < all.size(); i++)
                env.out().println(String.format("  %2d  %-6s %-40s %s", i + 1, all.get(i).method(), all.get(i).path(), all.get(i).id()));
            String answer = env.human().promptHuman("Which operations? (numbers separated by commas, 'reads' for the reading ones, 'all')");
            if (answer == null || answer.isBlank()) {
                env.err().println("Error: nothing was chosen; give --operations or --read-only when there is no one to ask.");
                return null;
            }
            String a = answer.strip().toLowerCase(Locale.ROOT);
            if (a.equals("all")) return all;
            if (a.equals("reads")) return all.stream().filter(ApiOperation::isRead).toList();
            List<ApiOperation> picked = new ArrayList<>();
            for (String part : a.split("[,\\s]+")) {
                try {
                    int n = Integer.parseInt(part);
                    if (n < 1 || n > all.size()) throw new NumberFormatException();
                    picked.add(all.get(n - 1));
                } catch (NumberFormatException e) {
                    env.err().println("Error: " + part + " is not one of the numbers above.");
                    return null;
                }
            }
            return picked;
        }

        static String oneLine(String text) {
            String flat = text.replaceAll("\\s+", " ").strip();
            return flat.length() <= 100 ? flat : flat.substring(0, 100) + "...";
        }

        static String effectOf(ApiOperation op) {
            return op.isRead() ? "reads" : "changes";
        }

        /** The description as a script names it: an address as given, a file relative to the script. */
        static String locationFor(String spec, Path base) {
            if (spec.matches("(?i)https?://.*")) return spec;
            Path file = base.resolve(spec).normalize();
            Path from = base.normalize();
            return file.startsWith(from) ? from.relativize(file).toString().replace('\\', '/') : file.toString();
        }

        private static String declaration(String name, String where, ApiOperation op, OpenApi c) {
            StringBuilder b = new StringBuilder();
            b.append("// ").append(op.method()).append(' ').append(op.path()).append(op.summary().isBlank() ? "" : "  " + oneLine(op.summary())).append('\n');
            b.append("tool ").append(name).append(" {\n");
            b.append("    use: openapi\n");
            b.append("    spec: \"").append(where).append("\"\n");
            b.append("    operation: \"").append(op.id()).append("\"\n");
            b.append("    effect: ").append(effectOf(op)).append('\n');
            b.append("    on_unknown: ").append(op.isRead() ? "retry" : "skip").append('\n');
            if (c.auth != null) {
                b.append("    auth_header: \"").append(c.authHeader).append("\"\n");
                b.append("    auth_value: ").append(c.auth).append('\n');
            }
            b.append("    pin: \"").append(SpecPin.of(op)).append("\"\n");
            b.append("}\n");
            return b.toString();
        }

        // ── --refresh ────────────────────────────────────────────────────────────────────────

        private static int refresh(OpenApi c, WeaveEnv env) {
            Path script = Path.of(c.spec).toAbsolutePath();
            LoomScript loaded;
            try {
                loaded = new LoomLoader().load(script.toString());
            } catch (Exception e) {
                env.err().println("Error: " + c.spec + " could not be read: " + e.getMessage());
                return 2;
            }
            Map<String, ApiDescription> read = new LinkedHashMap<>();
            List<String> drift = new ArrayList<>();
            List<String> unpinned = new ArrayList<>();
            int seen = 0;
            for (ToolDef t : loaded.getTools()) {
                if (!"openapi".equals(t.getKind()) || !t.getOptions().containsKey("operation")) continue;
                seen++;
                ToolDef.OptionValue pin = t.getOptions().get("pin");
                ToolDef.OptionValue spec = t.getOptions().get("spec");
                String operation = t.getOptions().get("operation").value();
                if (pin == null) {
                    unpinned.add(t.getName());
                    continue;
                }
                ApiDescription description;
                try {
                    description = read.computeIfAbsent(spec.value(), s -> OpenApiKind.read(s, script.getParent()));
                } catch (IllegalArgumentException e) {
                    drift.add(t.getName() + ": " + spec.value() + " could not be read now: " + e.getMessage());
                    continue;
                }
                var op = description.operation(operation);
                if (op.isEmpty()) drift.add(t.getName() + ": " + operation + " is no longer an operation of " + spec.value());
                else if (!SpecPin.of(op.get()).equals(pin.value()))
                    drift.add(t.getName() + ": the signature of " + operation + " changed (pinned " + pin.value() + ", now " + SpecPin.of(op.get()) + ")");
            }
            if (seen == 0) {
                env.err().println("Error: " + c.spec + " declares no openapi tool with an operation:.");
                return 2;
            }
            for (String u : unpinned) env.out().println("hint: " + u + " has no pin:; run weave connect openapi <spec> --write to write one.");
            if (drift.isEmpty()) {
                env.out().println("No drift: every pinned openapi tool matches its description as it is now.");
                return 0;
            }
            env.out().println("Drift against the pins:");
            drift.forEach(d -> env.out().println("  " + d));
            if (!c.check)
                env.out().println("Review what changed, then run weave connect openapi <spec> --write again for the operations you accept; --refresh never rewrites a pin by itself.");
            return 1;
        }
    }

    // ── mcp ───────────────────────────────────────────────────────────────────────────────

    @Command(
            name = "mcp",
            mixinStandardHelpOptions = true,
            description =
                    "Starts an MCP server under the isolation you name (or reaches it at an https address), lists its tools with the effect each would get (from its annotations, the server's own untrusted word) and writes the mcp block with isolation:, effects: and pin:. Without --write it only prints. With --refresh the argument is a script, and each pinned mcp block is read again.")
    static final class Mcp implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "<command|url|script>", description = "The program that starts the server, or its https address; with --refresh, the script.")
        String target;

        @Option(names = "--arg", paramLabel = "<argument>", description = "An argument of the program (repeatable; each one stays one argument).")
        List<String> args = new ArrayList<>();

        @Option(names = "--name", paramLabel = "<name>", description = "The block's name (default: Server).")
        String name = "Server";

        @Option(names = "--isolation", paramLabel = "<level>", description = "none, process (the default) or container.")
        String isolation = "process";

        @Option(names = "--image", paramLabel = "<image>", description = "The image of isolation: container.")
        String image;

        @Option(names = "--workspace", paramLabel = "<dir>", description = "The folder a container sees as its workspace.")
        String workspace;

        @Option(names = "--network", paramLabel = "<mode>", description = "none (the default) or open, for a container.")
        String network;

        @Option(names = "--auth", paramLabel = "<ref>", description = "For an https server: auth: env.NAME, secret.NAME or invoker.NAME. Never a value.")
        String auth;

        @Option(names = "--list-auth", paramLabel = "<ref>", description = "For an https server whose auth is invoker.NAME: the credential used only to list the tools.")
        String listAuth;

        @Option(names = "--operations", split = ",", paramLabel = "<tools>", description = "Only declare effects for these tools.")
        List<String> operations = new ArrayList<>();

        @Option(names = "--read-only", description = "Refuse to write when the server has a tool that is not proposed as reading.")
        boolean readOnly;

        @Option(names = "--write", paramLabel = "<script>", description = "Add the block to this script, after showing the change and asking.")
        File write;

        @Option(names = "--yes", description = "The effects as proposed are approved: do not ask (the change is still shown).")
        boolean yes;

        @Option(names = "--refresh", description = "Start each pinned mcp block of the script again and report what changed.")
        boolean refresh;

        @Option(names = "--check", description = "With --refresh: change nothing, print the drift, and exit 1 when there is any (for CI).")
        boolean check;

        @CommandLine.Mixin private EnvFileOptions envFile = new EnvFileOptions();

        @CommandLine.Mixin private SecretOptions secrets = new SecretOptions();

        @Override
        public Integer call() {
            Path anchor = write != null ? write.toPath() : refresh ? Path.of(target) : Path.of("connect.loom");
            WeaveEnv env = envFile.apply(WeaveEnv.system(), anchor);
            if (env == null) return 2;
            env = secrets.apply(env, Prompts.console());
            return env == null ? 2 : run(this, env);
        }

        static int run(Mcp c, WeaveEnv env) {
            if (c.check && !c.refresh) {
                env.err().println("Error: --check goes with --refresh.");
                return 2;
            }
            return c.refresh ? refresh(c, env) : connect(c, env);
        }

        private static int connect(Mcp c, WeaveEnv env) {
            if (!IDENTIFIER.matcher(c.name).matches()) {
                env.err().println("Error: --name must be a name made of letters, digits and underscores.");
                return 2;
            }
            for (String ref : java.util.Arrays.asList(c.auth, c.listAuth))
                if (ref != null && !REFERENCE.matcher(ref).matches()) {
                    env.err().println("Error: --auth and --list-auth say where the credential comes from (env.NAME, secret.NAME or invoker.NAME), never the credential itself.");
                    return 2;
                }
            boolean remote = c.target.matches("(?i)https?://.*");
            McpServerDef def = new McpServerDef(c.name);
            if (remote) {
                def.setTransport("http");
                def.setUrl(c.target);
                if (c.auth != null) def.setAuth(reference(c.auth));
                if (c.listAuth != null) def.setListAuth(reference(c.listAuth));
            } else {
                def.setCmd(c.target.strip());
                c.args.forEach(def::addArg);
                def.setIsolation(c.isolation);
                def.setImage(c.image);
                def.setWorkspace(c.workspace);
                def.setNetwork(c.network);
            }
            Path base = c.write == null ? Path.of("").toAbsolutePath() : c.write.toPath().toAbsolutePath().getParent();
            McpDiscovery.Result found;
            try {
                found = McpDiscovery.discover(def, credentials(env), base);
            } catch (LoomLoadException e) {
                env.err().println("Error: " + describe(e));
                return 1;
            }
            Map<String, TaskEffect> proposed = new LinkedHashMap<>();
            for (McpDiscovery.Tool t : found.tools())
                if (c.operations.isEmpty() || c.operations.contains(t.name())) proposed.put(t.name(), t.proposal().effect());
            env.out().println("The server " + c.name + " lists " + found.tools().size() + " tools. The annotations are the server's own word and are untrusted: the effects below are proposals you approve.");
            for (McpDiscovery.Tool t : found.tools())
                env.out().println(String.format("  %-28s %-8s %s", t.name(), proposed.containsKey(t.name()) ? proposed.get(t.name()).name().toLowerCase(Locale.ROOT) : "(not declared)", t.proposal().why()));
            if (c.readOnly) {
                List<String> writers = proposed.entrySet().stream().filter(e -> e.getValue() != TaskEffect.READS && e.getValue() != TaskEffect.NONE).map(Map.Entry::getKey).toList();
                if (!writers.isEmpty()) {
                    env.err().println("Error: --read-only, but the server has tools that are not proposed as reading: " + writers + ". Nothing was written.");
                    return 1;
                }
            }
            String block = block(c, found, proposed, remote);
            if (c.write == null) {
                env.out().println();
                env.out().println("Nothing is written (give --write <script>). The block would be:");
                env.out().println();
                env.out().print(block);
                return 0;
            }
            try {
                String before = Files.readString(c.write.toPath());
                if (toolNames(c.write.toPath(), env).contains(c.name) || before.contains("mcp " + c.name + " ")) {
                    env.err().println("Error: the script already has a block named " + c.name + "; give --name, or use weave mcp list --write to update it.");
                    return 2;
                }
                return ConnectEdit.apply(c.write.toPath().toAbsolutePath(), before, ConnectEdit.append(before, block), Set.of(c.name), c.yes, env);
            } catch (IOException e) {
                env.err().println("Error: " + c.write + " could not be read: " + e.getMessage());
                return 2;
            }
        }

        private static ToolDef.OptionValue reference(String text) {
            String name = text.substring(text.indexOf('.') + 1);
            return switch (text.substring(0, text.indexOf('.'))) {
                case "env" -> ToolDef.OptionValue.env(name);
                case "secret" -> ToolDef.OptionValue.secret(name);
                default -> ToolDef.OptionValue.invoker(name);
            };
        }

        static String block(Mcp c, McpDiscovery.Result found, Map<String, TaskEffect> proposed, boolean remote) {
            StringBuilder b = new StringBuilder();
            b.append("mcp ").append(c.name).append(" {\n");
            if (remote) {
                b.append("    transport: \"http\"\n    url: \"").append(c.target).append("\"\n");
                if (c.auth != null) b.append("    auth: ").append(c.auth).append('\n');
                if (c.listAuth != null) b.append("    list_auth: ").append(c.listAuth).append('\n');
            } else {
                b.append("    transport: \"stdio\"\n    cmd: \"").append(escape(c.target.strip())).append("\"\n");
                if (!c.args.isEmpty()) b.append("    args: [").append(String.join(", ", c.args.stream().map(a -> "\"" + escape(a) + "\"").toList())).append("]\n");
                b.append("    isolation: ").append(c.isolation).append('\n');
                if ("container".equals(c.isolation)) {
                    if (c.image != null) b.append("    image: \"").append(escape(c.image)).append("\"\n");
                    if (c.workspace != null) b.append("    workspace: \"").append(escape(c.workspace)).append("\"\n");
                    if (c.network != null) b.append("    network: ").append(c.network).append('\n');
                }
            }
            if (!proposed.isEmpty()) {
                List<String> parts = new ArrayList<>();
                proposed.forEach((tool, effect) -> parts.add(spell(tool) + ": " + effect.name().toLowerCase(Locale.ROOT)));
                b.append("    effects: { ").append(String.join(", ", parts)).append(" }\n");
            }
            b.append("    pin: \"").append(found.pin()).append("\"\n}\n");
            return b.toString();
        }

        private static String spell(String tool) {
            return McpScriptEditor.spell(tool);
        }

        private static String escape(String text) {
            return text.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static String describe(LoomLoadException e) {
            return e.problems().stream().map(ScriptValidator.Problem::message).reduce((a, b) -> a + "; " + b).orElse(e.getMessage());
        }

        static java.util.function.Function<String, String> credentials(WeaveEnv env) {
            return key -> {
                var store = env.secrets();
                if (key.startsWith("secret:")) {
                    String n = key.substring("secret:".length());
                    return store != null && store.contains(n) ? store.resolve(n) : null;
                }
                if (store != null && io.github.llm4j.secret.SecretNames.isValid(key) && store.contains(key)) return store.resolve(key);
                return env.env().apply(key);
            };
        }

        private static int refresh(Mcp c, WeaveEnv env) {
            Path script = Path.of(c.target).toAbsolutePath();
            LoomScript loaded;
            try {
                loaded = new LoomLoader().load(script.toString());
            } catch (Exception e) {
                env.err().println("Error: " + c.target + " could not be read: " + e.getMessage());
                return 2;
            }
            List<String> drift = new ArrayList<>();
            int seen = 0;
            for (McpServerDef def : loaded.getMcpServers()) {
                if (def.getPin() == null) {
                    env.out().println("hint: mcp " + def.getName() + " has no pin:; run weave mcp list --write to write one.");
                    continue;
                }
                seen++;
                try {
                    McpDiscovery.Result now = McpDiscovery.discover(def, credentials(env), script.getParent());
                    if (!def.getPin().equals(now.pin()))
                        drift.add("mcp " + def.getName() + ": the tool list changed (pinned " + def.getPin() + ", now " + now.pin() + "); run weave mcp list to see the tools");
                } catch (LoomLoadException e) {
                    drift.add("mcp " + def.getName() + ": could not be started or listed now: " + describe(e));
                }
            }
            if (seen == 0) {
                env.err().println("Error: " + c.target + " declares no pinned mcp block.");
                return 2;
            }
            if (drift.isEmpty()) {
                env.out().println("No drift: every pinned mcp block lists the tools it was pinned to.");
                return 0;
            }
            env.out().println("Drift against the pins:");
            drift.forEach(d -> env.out().println("  " + d));
            return 1;
        }
    }

    /** The names a script already declares as tools or mcp blocks (a script that cannot be loaded has none). */
    static Set<String> toolNames(Path script, WeaveEnv env) {
        Set<String> names = new LinkedHashSet<>();
        try {
            LoomScript loaded = new LoomLoader().load(script.toAbsolutePath().toString());
            loaded.getTools().forEach(t -> names.add(t.getName()));
            loaded.getMcpServers().forEach(m -> names.add(m.getName()));
        } catch (Exception e) {
            env.err().println("warning: " + script + " could not be read to look for names already in use: " + e.getMessage());
        }
        return names;
    }
}
