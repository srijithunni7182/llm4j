package io.github.llm4j.loom.ast;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AST node representing an MCP (Model Context Protocol) server declaration.
 *
 * <p>Example {@code .loom} syntax:
 *
 * <pre>
 * mcp PostgresDB {
 *     transport: "stdio"
 *     cmd: "npx @modelcontextprotocol/server-postgres"
 * }
 * </pre>
 */
public class McpServerDef implements Node {
    private int line;

    /** The line it was declared on, for error messages. */
    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    private final String name;
    private String transport = "stdio";
    private String cmd;

    /** Optional extra environment variables passed to the server process. */
    private final Map<String, String> env = new LinkedHashMap<>();

    private final Map<String, io.github.llm4j.agent.task.TaskEffect> toolEffects =
            new LinkedHashMap<>();

    /** The effect the developer declared for each of this server's tools, by tool name. */
    public Map<String, io.github.llm4j.agent.task.TaskEffect> getToolEffects() {
        return Collections.unmodifiableMap(toolEffects);
    }

    public void putToolEffect(String tool, io.github.llm4j.agent.task.TaskEffect effect) {
        toolEffects.put(tool, effect);
    }

    public McpServerDef(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getTransport() {
        return transport;
    }

    public String getCmd() {
        return cmd;
    }

    public Map<String, String> getEnv() {
        return Collections.unmodifiableMap(env);
    }

    private final java.util.List<String> args = new java.util.ArrayList<>();
    private final Map<String, ToolDef.OptionValue> envValues = new LinkedHashMap<>();
    private final java.util.List<String> envInherit = new java.util.ArrayList<>();
    private String isolation;
    private java.time.Duration timeout;
    private String reach;
    private String pin;

    /** The program's arguments, each one argument and never split ({@code args: [..]}). */
    public java.util.List<String> getArgs() {
        return java.util.Collections.unmodifiableList(args);
    }

    public void addArg(String arg) {
        args.add(arg);
    }

    /**
     * The variables the server is given ({@code env: { NAME: env.X }}): a literal, or a reference
     * to the environment or the secret store, resolved when the server starts.
     */
    public Map<String, ToolDef.OptionValue> getEnvValues() {
        return Collections.unmodifiableMap(envValues);
    }

    public void putEnvValue(String name, ToolDef.OptionValue value) {
        envValues.put(name, value);
    }

    /** Names of host variables an isolated server may inherit ({@code env_inherit: [..]}). */
    public java.util.List<String> getEnvInherit() {
        return java.util.Collections.unmodifiableList(envInherit);
    }

    public void addEnvInherit(String name) {
        envInherit.add(name);
    }

    /**
     * {@code none} or {@code process}, or null when the block has no {@code isolation:} line (the
     * legacy state: the server runs with this process's whole environment).
     */
    public String getIsolation() {
        return isolation;
    }

    public void setIsolation(String isolation) {
        this.isolation = isolation;
    }

    /** How long one request to the server waits, or null for the default. */
    public java.time.Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(java.time.Duration timeout) {
        this.timeout = timeout;
    }

    /** What the developer says the server reaches ({@code none}, {@code reads}, ...), or null. */
    public String getReach() {
        return reach;
    }

    public void setReach(String reach) {
        this.reach = reach;
    }

    /** The hash of the server's tool list this block was written against, or null. */
    public String getPin() {
        return pin;
    }

    public void setPin(String pin) {
        this.pin = pin;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public void setCmd(String cmd) {
        this.cmd = cmd;
    }

    public void addEnv(String key, String val) {
        this.env.put(key, val);
    }
}
