package io.github.llm4j.loom.ast;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AST node representing an MCP (Model Context Protocol) server declaration.
 *
 * <p>Example {@code .loom} syntax:
 * <pre>
 * mcp PostgresDB {
 *     transport: "stdio"
 *     cmd: "npx @modelcontextprotocol/server-postgres"
 * }
 * </pre>
 */
public class McpServerDef implements Node {
    private final String name;
    private String transport = "stdio";
    private String cmd;
    /** Optional extra environment variables passed to the server process. */
    private final Map<String, String> env = new LinkedHashMap<>();

    public McpServerDef(String name) {
        this.name = name;
    }

    public String getName()      { return name; }
    public String getTransport() { return transport; }
    public String getCmd()       { return cmd; }
    public Map<String, String> getEnv() { return Collections.unmodifiableMap(env); }

    public void setTransport(String transport) { this.transport = transport; }
    public void setCmd(String cmd)             { this.cmd = cmd; }
    public void addEnv(String key, String val) { this.env.put(key, val); }
}
