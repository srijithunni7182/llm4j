package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.Map;

/**
 * Test access: boots the servers of a script as the engine does and asks the run's own
 * classification of one of their tools.
 */
public final class McpEffectsAccess {
    private McpEffectsAccess() {}

    public static boolean isRead(String script, String toolName) {
        var parsed = new LoomParser(new Lexer(script).tokenize()).parseScript();
        McpServers servers = new McpServers();
        try {
            servers.boot(parsed.getMcpServers(), name -> null);
            for (Tool tool : servers.toolsOf("Fake"))
                if (tool.getName().equals(toolName)) return ReadClass.isRead(tool, Map.of());
            throw new IllegalArgumentException("no tool " + toolName);
        } finally {
            servers.close();
        }
    }
}
