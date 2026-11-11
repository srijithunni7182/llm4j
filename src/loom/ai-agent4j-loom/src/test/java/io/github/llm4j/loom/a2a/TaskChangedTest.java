package io.github.llm4j.loom.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.a2a.AgentChannel;
import io.github.llm4j.a2a.ChannelResponse;
import io.github.llm4j.a2a.testkit.MockA2AAgent;
import io.github.llm4j.a2a.testkit.MockA2AAgent.Script;
import io.github.llm4j.a2a.testkit.MockA2AAgent.Step;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.host.LoomHost;
import io.github.llm4j.loom.host.Persistence;
import io.github.llm4j.loom.host.RemoteTasks;
import io.github.llm4j.loom.host.RunOutcome;
import io.github.llm4j.loom.host.RunRecord;
import io.github.llm4j.loom.host.RunRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The host says a remote task changed: the run carries on once, and a repeat or a stale notice changes nothing. */
class TaskChangedTest {

    private static final String PUBLIC = "https://agent.example.test";

    @TempDir Path dir;

    private MockA2AAgent mock;
    private LoomHost host;
    private RemoteTasks tasks;

    @BeforeEach
    void start() throws Exception {
        mock = MockA2AAgent.start(PUBLIC);
        Path script = dir.resolve("main.loom");
        Files.writeString(script, """
                agent_channel Desk { audience: staff }
                remote Supplier {
                    card: "https://agent.example.test/.well-known/agent-card.json"
                    agent_channel: Desk
                }
                workflow Buy(part) { delegate "Quote {part}" to Supplier -> quote }
                """);
        AgentChannel desk = (c, m) -> new ChannelResponse.Reply("Within 30 days.", null);
        host = LoomHost.builder(new LoomLoader().load(script.toString()), new ToolRegistry(), model -> null)
                .persistence(Persistence.file(dir.resolve("store")))
                .scriptRef(script.toAbsolutePath().toString())
                .baseDir(dir)
                .customizer(RemoteTasks.wiring(new LoopbackTransport(PUBLIC, mock.loopbackBase()), Map.of("Desk", desk)))
                .build();
        tasks = new RemoteTasks(host);
    }

    @AfterEach
    void stop() {
        host.close();
        mock.close();
    }

    @Test
    void theRunPausesAndTaskChangedCarriesItOnExactlyOnce() {
        mock.script(Script.of(Step.working(), Step.completedWithText("42 each")));

        RunOutcome first = host.run(RunRequest.of("Buy", Map.of("part", "12")).withRunId("r1"));
        assertThat(first).isInstanceOf(RunOutcome.Suspended.class);
        RunRecord waiting = host.run("r1").orElseThrow();
        assertThat(waiting.status()).isEqualTo(RunRecord.Status.SUSPENDED);
        String taskId = "task-1";

        assertThat(tasks.taskChanged("r1", "some-other-task")).as("a stale notice").isEmpty();
        assertThat(tasks.taskChanged("nope", taskId)).as("an unknown run").isEmpty();

        var resumed = tasks.taskChanged("r1", taskId);
        assertThat(resumed).isPresent();
        assertThat(resumed.get()).isInstanceOf(RunOutcome.Completed.class);

        assertThat(tasks.taskChanged("r1", taskId)).as("a duplicate delivery").isEmpty();
        assertThat(mock.receivedMessageIds()).as("the message was sent once").hasSize(1);
    }
}
