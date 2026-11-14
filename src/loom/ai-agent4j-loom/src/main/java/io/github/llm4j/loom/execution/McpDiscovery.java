package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.ast.McpServerDef;
import io.github.llm4j.loom.security.McpEffects;
import io.github.llm4j.mcp.McpPin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Starts an MCP server as its block declares, asks it for its tools, stops it, and says what the
 * annotations suggest for each (phase 36a, D4): the read-only half of {@code weave mcp list}, and the
 * caller {@link McpEffects#propose} had none of. It never calls a tool.
 */
public final class McpDiscovery {

    private McpDiscovery() {}

    /**
     * One tool of the server.
     *
     * @param name the tool's name
     * @param declared what the script already declares for it, or null
     * @param proposal what its annotations suggest (the server's word, untrusted)
     * @param annotations the annotations as the server gave them, or an empty map
     */
    public record Tool(
            String name,
            TaskEffect declared,
            McpEffects.Proposal proposal,
            Map<String, Object> annotations) {}

    /**
     * What a server offers.
     *
     * @param server the block's name
     * @param command the program the block starts
     * @param isolation the block's isolation, or null when it has none
     * @param tools the tools, in the order listed
     * @param pin the pin of this tool list
     * @param declaredPin the pin the block carries, or null
     * @param listed the tools as the server gave them
     */
    public record Result(
            String server,
            String command,
            String isolation,
            List<Tool> tools,
            String pin,
            String declaredPin,
            List<Map<String, Object>> listed) {}

    /**
     * @param credentials resolves {@code env.NAME} and {@code secret.NAME} for the server's variables
     * @throws LoomLoadException when the server cannot start or cannot list its tools
     */
    public static Result discover(McpServerDef def, Function<String, String> credentials) {
        McpServers servers = new McpServers();
        try {
            servers.boot(List.of(def), credentials);
            List<Map<String, Object>> listed = servers.listed(def.getName());
            List<Tool> tools = new ArrayList<>();
            for (Map<String, Object> t : listed) {
                String name = String.valueOf(t.get("name"));
                @SuppressWarnings("unchecked")
                Map<String, Object> annotations =
                        t.get("annotations") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                tools.add(
                        new Tool(
                                name,
                                def.getToolEffects().get(name),
                                McpEffects.propose(t),
                                annotations));
            }
            return new Result(
                    def.getName(),
                    def.getCmd(),
                    def.getIsolation(),
                    tools,
                    McpPin.of(listed),
                    def.getPin(),
                    listed);
        } finally {
            servers.close();
        }
    }
}
