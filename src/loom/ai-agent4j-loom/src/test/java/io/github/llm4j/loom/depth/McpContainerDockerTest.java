package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.loom.ast.McpServerDef;
import io.github.llm4j.loom.execution.McpDiscovery;
import io.github.llm4j.tools.ContainerSandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 36c, G85 against a real Docker daemon: an MCP server run with {@code isolation: container}
 * answers the handshake and lists its tools, sees only its workspace, and has no network. Runs only
 * where a daemon answers and the image is present or can be pulled.
 */
@Tag("integration")
class McpContainerDockerTest {

    static final String IMAGE = "python:3.12-alpine";

    @TempDir Path dir;

    /** A server in a few lines of Python: it lists one tool whose description is what it can see. */
    private static final String SERVER =
            """
            import json, os, sys
            def reach():
                # with no network the container has only its loopback interface
                return "-".join(sorted(os.listdir("/sys/class/net")))
            def see():
                return ",".join(sorted(os.listdir("/workspace")))
            for line in sys.stdin:
                m = json.loads(line)
                if "id" not in m:
                    continue
                if m["method"] == "initialize":
                    r = {"protocolVersion": m["params"]["protocolVersion"], "capabilities": {"tools": {}}, "serverInfo": {"name": "t", "version": "1"}}
                elif m["method"] == "tools/list":
                    r = {"tools": [{"name": "look", "description": see() + "|" + reach(), "inputSchema": {"type": "object"}}]}
                else:
                    r = {}
                print(json.dumps({"jsonrpc": "2.0", "id": m["id"], "result": r}), flush=True)
            """;

    @Test
    void aServerInAContainerSeesOnlyItsWorkspaceAndHasNoNetwork() throws Exception {
        assumeTrue(new ContainerSandbox("docker", IMAGE, false).probe() == null, "no Docker daemon answers here");
        Files.createDirectories(dir.resolve("work"));
        Files.writeString(dir.resolve("work/server.py"), SERVER);
        Files.writeString(dir.resolve("work/data.txt"), "x");
        Files.writeString(dir.resolve("outside.txt"), "not visible");

        McpServerDef def = new McpServerDef("Boxed");
        def.setCmd("python3");
        def.addArg("/workspace/server.py");
        def.setIsolation("container");
        def.setImage(IMAGE);
        def.setWorkspace("work");

        McpDiscovery.Result result = McpDiscovery.discover(def, name -> null, dir);

        String seen = String.valueOf(result.listed().get(0).get("description"));
        assertThat(seen).contains("data.txt").contains("server.py").doesNotContain("outside.txt").endsWith("|lo");
    }
}
