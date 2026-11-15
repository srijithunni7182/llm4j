package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.mcp.testkit.FakeMcpServer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Two servers that list the same tool name: one server's declared read never vouches for the other's tool. */
class McpServersSharedNameTest {

    private static String block(String name, String extra) {
        List<String> command = FakeMcpServer.command();
        List<String> quoted = new ArrayList<>();
        for (String a : command.subList(1, command.size())) quoted.add("\"" + a + "\"");
        return "mcp " + name + " { transport: \"stdio\" cmd: \"" + command.get(0) + "\" args: [" + String.join(", ", quoted) + "] isolation: process " + extra + " }\n";
    }

    @Test
    void aSharedToolNameIsNotChangingOnlyWhenEveryServerSaysSo() {
        var script = new LoomParser(new Lexer(block("A", "effects: { echo: reads }") + block("B", "") + "workflow Main() { note \"x\" }").tokenize()).parseScript();
        McpServers servers = new McpServers();
        try {
            servers.boot(script.getMcpServers(), k -> null);

            assertThat(servers.notChanging(List.of("A"))).contains("echo");
            assertThat(servers.notChanging(List.of("A", "B"))).as("B's undeclared echo may change things").doesNotContain("echo");
        } finally {
            servers.close();
        }
    }
}
