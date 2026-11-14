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

    /** The levels an MCP server can run at today; {@code container} comes with the sandbox. */
    static final java.util.Set<String> LEVELS = java.util.Set.of("none", "process");

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
                            + " is not available for an mcp server yet; use none or process");
        } else if (m.getCmd().strip().matches(".*\\s.*")) {
            c.error(
                    m.getLine(),
                    construct,
                    "with isolation: cmd is one program; put its arguments in args: [\"..\"], each one"
                            + " argument");
        }
        m.getEnvValues().forEach((name, value) -> checkVariable(c, m, name, value));
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
