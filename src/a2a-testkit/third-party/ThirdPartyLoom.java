import io.github.llm4j.a2a.*;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.host.*;
import java.nio.file.*;
import java.util.Map;

/** Scratch: a Loom workflow delegating to a third-party A2A agent (through the transport that makes its loopback card pass the endpoint guard). */
public class ThirdPartyLoom {
    public static void main(String[] a) throws Exception {
        ThirdPartyCheck.base = a[0];
        Path dir = Files.createTempDirectory("loom-3p");
        Path script = dir.resolve("main.loom");
        Files.writeString(script, """
                agent_channel Desk { audience: staff }
                remote Hello {
                    card: "https://agent.example.test/.well-known/agent-card.json"
                    agent_channel: Desk
                    timeout: 5m
                }
                workflow Greet(name) {
                    delegate "hello from {name}" to Hello -> reply
                    return reply
                }
                """);
        var loaded = new LoomLoader().load(script.toString());
        AgentChannel desk = (c, m) -> new ChannelResponse.Refuse("no questions expected");
        var host = LoomHost.builder(loaded, new ToolRegistry(), model -> null)
                .persistence(Persistence.file(dir.resolve("store")))
                .scriptRef(script.toAbsolutePath().toString()).baseDir(dir)
                .customizer(RemoteTasks.wiring(new ThirdPartyCheck.RewritingTransport(), Map.of("Desk", desk)))
                .build();
        RunOutcome first = host.run(RunRequest.of("Greet", Map.of("name", "llm4j")).withRunId("r1"));
        System.out.println("first: " + first);
        for (int i = 0; i < 5 && !(host.run("r1").orElseThrow().status() == RunRecord.Status.COMPLETED); i++) {
            Thread.sleep(500);
            var rec = host.run("r1").orElseThrow();
            if (rec.status() == RunRecord.Status.SUSPENDED) System.out.println("resume: " + host.resume("r1", null));
        }
        var rec = host.run("r1").orElseThrow();
        System.out.println("final status=" + rec.status());
        host.close();
    }
}
