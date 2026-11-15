package io.github.llm4j.loom.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.mcp.testkit.FakeMcpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 36, D15 and G93: no model-written value becomes trusted by passing through an {@code openapi}
 * operation, a {@code command} or an MCP tool, all of which can echo what they were sent. The mock model's
 * answer carries the canary "mock"; at the exit (a tool of the host's that changes things) the runtime's
 * belief "a model wrote this" must hold wherever the canary went.
 */
class IntegrationResultProvenanceTest {

    @TempDir Path dir;

    record Exit(String value, boolean modelWritten) {}

    List<Exit> run(String script) throws Exception {
        Path file = Files.writeString(dir.resolve("main.loom"), script);
        MockModels mock = new MockModels();
        ToolRegistry host = new ToolRegistry();
        host.register("Hook", new io.github.llm4j.agent.tools.EchoTool());
        HarnessExecutor e = new HarnessExecutor(new LoomLoader().load(file.toString()), host, mock::createClient);
        e.setBaseDir(dir);
        e.setEnvLookup(k -> "https://example.invalid/hook");
        e.setHumanInterface(m -> "yes");
        List<Exit> exits = new ArrayList<>();
        e.setSinkOracle((sink, value, modelWritten) -> exits.add(new Exit(String.valueOf(value), modelWritten)));
        e.initialize();
        try {
            e.executeWorkflow("Main", Map.of("q", "go"));
        } catch (RuntimeException failed) {
            throw new AssertionError("the workflow should run to the end", failed);
        } finally {
            e.shutdown();
        }
        return exits;
    }

    @Test
    void aCommandThatEchoesAModelsAnswerHandsBackTheModelsText() throws Exception {
        Files.createDirectories(dir.resolve("work"));
        List<Exit> exits =
                run(
                        """
                        agent A { model: "m"  system: "s" }
                        tool Data { use: workspace  root: "work"  allow: "**" }
                        tool Say  { use: command  isolation: process  network: open  workspace: Data  run: "printf %s {text}"  param.text: "string max=200" }
                        workflow Main(q) {
                            delegate "{q}" to A -> answer
                            use Say(text = answer) -> printed
                            use Hook(text = "{printed}")
                        }
                        """);

        assertThat(exits).isNotEmpty();
        Exit hook = exits.get(exits.size() - 1);
        assertThat(hook.value()).contains("mock");
        assertThat(hook.modelWritten()).as("a program's output is unchecked text").isTrue();
    }

    @Test
    void anApiThatEchoesWhatItWasSentHandsBackTheModelsText() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            byte[] body = ("you said: " + exchange.getRequestURI().getRawQuery()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Files.writeString(
                    dir.resolve("echo.json"),
                    "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Echo\",\"version\":\"1\"},\"paths\":{\"/echo\":{\"get\":{\"operationId\":\"echo\","
                            + "\"parameters\":[{\"name\":\"text\",\"in\":\"query\",\"required\":true,\"schema\":{\"type\":\"string\"}}],"
                            + "\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}");
            List<Exit> exits =
                    run(
                            "agent A { model: \"m\"  system: \"s\" }\n"
                                    + "tool Echo { use: openapi  spec: \"echo.json\"  operation: \"echo\"  effect: reads  base_url: \"http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "\"  allow_http: true  allow_private: true }\n"
                                    + "workflow Main(q) {\n    delegate \"{q}\" to A -> answer\n    use Echo(text = answer) -> echoed\n    use Hook(text = \"{echoed}\")\n}\n");

            assertThat(exits).isNotEmpty();
            Exit hook = exits.get(exits.size() - 1);
            assertThat(hook.value()).contains("you said").contains("mock");
            assertThat(hook.modelWritten()).as("an API's answer is unchecked text").isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anMcpToolIsReachedOnlyThroughAnAgentWhoseAnswerIsTheModelsText() throws Exception {
        // a `use` statement cannot call a tool an MCP server brought (only an agent does, inside its step), so a
        // server's result reaches the workflow only as part of an agent's answer, which already carries "a model wrote this"
        List<String> command = FakeMcpServer.command();
        List<String> quoted = new ArrayList<>();
        for (String a : command.subList(1, command.size())) quoted.add("\"" + a + "\"");
        String block =
                "mcp Fake { transport: \"stdio\"  cmd: \""
                        + command.get(0)
                        + "\"  args: ["
                        + String.join(", ", quoted)
                        + "]  isolation: process  effects: { echo: reads } }\n";

        assertThatThrownBy(() -> run(block + "agent A { model: \"m\"  system: \"s\"  mcp_servers: [Fake] }\nworkflow Main(q) { use echo(text = q) -> r }\n"))
                .hasMessageContaining("echo is not a tool");

        List<Exit> exits =
                run(block + "agent A { model: \"m\"  system: \"s\"  mcp_servers: [Fake] }\nworkflow Main(q) {\n    delegate \"{q}\" to A -> answer\n    use Hook(text = \"{answer}\")\n}\n");
        assertThat(exits.get(exits.size() - 1).modelWritten()).isTrue();
    }
}
