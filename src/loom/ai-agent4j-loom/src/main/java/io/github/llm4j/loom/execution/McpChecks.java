package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.McpServerDef;
import io.github.llm4j.loom.ast.ToolDef;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The static checks of an {@code mcp} block (phase 36, D7): the program to start, how it is
 * isolated, and what it is given. A server is a program started with the script's permissions, so a
 * block with no {@code isolation:} line still loads, as it always did, but says so.
 */
final class McpChecks {

    /** The levels an MCP server can run at. */
    static final java.util.Set<String> LEVELS = java.util.Set.of("none", "process", "container");

    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern CREDENTIAL_NAME =
            Pattern.compile(
                    ".*(KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|AUTH).*", Pattern.CASE_INSENSITIVE);

    private McpChecks() {}

    static void run(ScriptValidator.Checker c) {
        for (McpServerDef m : c.script().getMcpServers()) check(c, m);
    }

    private static void check(ScriptValidator.Checker c, McpServerDef m) {
        String construct = "mcp " + m.getName();
        if (m.isRemote()) {
            checkRemote(c, m);
            return;
        }
        if (m.getUrl() != null || m.getAuth() != null || m.getListAuth() != null)
            c.error(m.getLine(), construct, "url:, auth: and list_auth: are for a remote server (transport: \"http\")");
        if (m.getCmd() == null || m.getCmd().isBlank()) {
            c.error(m.getLine(), construct, "needs cmd: \"<command>\"");
            return;
        }
        String level = m.getIsolation();
        if (level == null) {
            if (!m.getEnvInherit().isEmpty())
                c.error(
                        m.getLine(),
                        construct,
                        "env_inherit: only applies with an isolation: line (without one the server"
                                + " inherits the whole environment)");
            c.warn(
                    m.getLine(),
                    construct,
                    "runs `"
                            + m.getCmd()
                            + "` with this process's whole environment and permissions; add"
                            + " isolation: process (a scrubbed environment and a captured error stream),"
                            + " or isolation: none to accept running it on the host");
        } else if (!LEVELS.contains(level)) {
            c.error(
                    m.getLine(),
                    construct,
                    "isolation: "
                            + level
                            + " is not an isolation level; use none, process or container");
        } else if (level.equals("container")) {
            checkContainer(c, m, construct);
        } else if (m.getCmd().strip().matches(".*\\s.*")) {
            c.error(
                    m.getLine(),
                    construct,
                    "with isolation: cmd is one program; put its arguments in args: [\"..\"], each one"
                            + " argument");
        }
        if (!"container".equals(level) && (m.getImage() != null || m.getNetwork() != null || m.getWorkspace() != null))
            c.error(m.getLine(), construct, "image:, network: and workspace: go with isolation: container");
        m.getEnvValues().forEach((name, value) -> checkVariable(c, m, name, value));
    }

    private static void checkContainer(ScriptValidator.Checker c, McpServerDef m, String construct) {
        if (m.getImage() == null || m.getImage().isBlank())
            c.error(m.getLine(), construct, "isolation: container needs image: \"<image>\" (a pinned name, such as one ending in a digest)");
        else if (m.getImage().startsWith("-") || m.getImage().matches(".*\\s.*"))
            c.error(m.getLine(), construct, "image: is one image name, not a flag or a command line");
        if (m.getNetwork() != null && !java.util.Set.of("none", "open").contains(m.getNetwork()))
            c.error(m.getLine(), construct, "network: " + m.getNetwork() + " is not available for a server; use none or open");
        if (java.util.Set.of("reads", "writes").contains(String.valueOf(m.getReach())) && m.getWorkspace() == null)
            c.warn(m.getLine(), construct, "reach: " + m.getReach() + " in a container sees only the workspace: add workspace: \"<dir>\"");
    }

    /** A remote server is reached at an address, with a token that is never written in the script. */
    private static void checkRemote(ScriptValidator.Checker c, McpServerDef m) {
        String construct = "mcp " + m.getName();
        if (m.getUrl() == null || !m.getUrl().matches("(?i)https?://[^\\s/]+.*"))
            c.error(m.getLine(), construct, "a remote server needs url: \"https://…\"");
        if (m.getCmd() != null || m.getIsolation() != null || !m.getArgs().isEmpty() || !m.getEnvValues().isEmpty() || !m.getEnvInherit().isEmpty())
            c.error(m.getLine(), construct, "cmd:, args:, env:, env_inherit: and isolation: are for a program; a remote server has none of them");
        if (m.getUrl() != null && m.getUrl().matches("(?i)https?://[^/]*@.*"))
            c.error(m.getLine(), construct, "the url must not carry a user name or password; the token goes in auth:");
        for (var entry : java.util.List.of(java.util.Map.entry("auth", java.util.Optional.ofNullable(m.getAuth())), java.util.Map.entry("list_auth", java.util.Optional.ofNullable(m.getListAuth())))) {
            if (entry.getValue().isEmpty()) continue;
            ToolDef.OptionValue v = entry.getValue().get();
            if (!v.isReference() && !v.fromInvoker())
                c.error(m.getLine(), construct, entry.getKey() + ": must come from the environment, the secret store or the invoker (env.NAME, secret.NAME or invoker.NAME), never a literal");
            else if (entry.getKey().equals("list_auth") && v.fromInvoker())
                c.error(m.getLine(), construct, "list_auth: is used when the engine starts, before anyone runs the workflow, so it must be env.NAME or secret.NAME");
            else if (!v.fromInvoker() && v.isReference()) {
                String resolved = c.env().apply(v.lookupKey());
                if (resolved == null || resolved.isBlank())
                    c.error(m.getLine(), construct, (v.fromSecret() ? "secret " + v.value() + " is not in the secret store" : v.describe() + " is not set") + " (for " + entry.getKey() + ":)");
            }
        }
        if (m.getAuth() != null && m.getAuth().fromInvoker() && m.getListAuth() == null)
            c.error(m.getLine(), construct, "auth: invoker.NAME is asked for when a run calls a tool, but the tools are listed when the engine starts: give list_auth: env.NAME (a credential used only to list them)");
    }

    private static void checkVariable(
            ScriptValidator.Checker c, McpServerDef m, String name, ToolDef.OptionValue value) {
        String construct = "mcp " + m.getName();
        if (!VARIABLE.matcher(name).matches()) {
            c.error(m.getLine(), construct, "env: " + name + " is not a variable name");
            return;
        }
        if (value.fromInvoker()) {
            c.error(
                    m.getLine(),
                    construct,
                    "env: "
                            + name
                            + " can't come from "
                            + value
                            + ": a server program takes its credentials when it starts, so use env.NAME"
                            + " or secret.NAME");
            return;
        }
        if (!value.isReference()) {
            if (CREDENTIAL_NAME.matcher(name).matches())
                c.error(
                        m.getLine(),
                        construct,
                        "env: "
                                + name
                                + " looks like a credential, so it must come from the environment or the"
                                + " secret store, e.g. "
                                + name
                                + ": secret."
                                + name
                                + " (or env."
                                + name
                                + ")");
            return;
        }
        String resolved = c.env().apply(value.lookupKey());
        if (resolved == null || resolved.isBlank())
            c.error(
                    m.getLine(),
                    construct,
                    (value.fromSecret()
                                    ? "secret " + value.value() + " is not in the secret store"
                                    : value.describe() + " is not set")
                            + " (for env: "
                            + name
                            + ")");
    }

    /** What {@code env:} resolves to for a server: its literal and referenced values by name. */
    static Map<String, String> environmentOf(
            McpServerDef m, java.util.function.Function<String, String> lookup) {
        Map<String, String> out = new java.util.LinkedHashMap<>(m.getEnv());
        m.getEnvValues()
                .forEach(
                        (name, value) ->
                                out.put(
                                        name,
                                        value.isReference()
                                                ? lookup.apply(value.lookupKey())
                                                : value.value()));
        return out;
    }
}
