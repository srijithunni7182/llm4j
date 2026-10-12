package io.github.llm4j.loom.inspect.sim;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.fabric.FabricServer;
import io.github.llm4j.loom.fabric.Vault;
import io.github.llm4j.loom.inspect.state.RunState;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The simulated workflow runs end to end against a real Fabric server and shows what it is built to show. */
class SimulateTest {

    @TempDir
    Path dir;

    private FabricServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    @Test
    void aFastRunReachesFabricAndShowsParallelAgentsAFailingToolAndALoopThatDoesNotConverge() throws Exception {
        Vault vault = Vault.create(dir.resolve("vault.json"), "a-long-enough-master-key");
        String key = Vault.newClientKey();
        vault.put("client.sim-1", key);
        vault.put(Vault.ADMIN, "correct horse battery");
        server = new FabricServer(new FabricServer.Settings(dir.resolve("data"), "127.0.0.1", 0, null, false, 30, 1L << 30), vault, new PrintStream(new ByteArrayOutputStream()));
        server.start();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        int code = new Simulate.Simulation("http://127.0.0.1:" + server.port(), "sim-1", "UNUSED", null, null, false, 0, 1, 0, 0.01, "cold plunges", "truncated",
                dir.resolve("outbox").toString(), null, new PrintStream(log)).withKey(key).run();
        assertThat(code).isZero();
        assertThat(log.toString()).contains("run 1").contains("finished");

        long until = System.currentTimeMillis() + 10_000;
        RunState run = null;
        while (System.currentTimeMillis() < until) {
            run = server.registry().summaries().isEmpty() ? null : server.registry().find("sim-1", (String) server.registry().summaries().get(0).get("traceId")).orElse(null);
            if (run != null && "finished".equals(run.view().get("status"))) break;
            Thread.sleep(50);
        }
        assertThat(run).isNotNull();
        Map<String, Object> v = run.view();
        assertThat(v).containsEntry("status", "finished").containsEntry("workflow", "Studio");
        assertThat(String.valueOf(v.get("agents"))).contains("Researcher").contains("TrendScout").contains("Competitor"); // the broadcast targets are agents too
        assertThat(((Map<?, ?>) v.get("totals")).get("tokens")).isNotEqualTo(0L);
        assertThat(new java.math.BigDecimal(String.valueOf(((Map<?, ?>) v.get("totals")).get("cost")))).isPositive(); // priced from the price file
        String incidents = String.valueOf(v.get("incidents"));
        assertThat(incidents).contains("Editor").contains("Scriptwriter"); // the repeated call and the invented figures
        assertThat(String.valueOf(v.get("tools"))).contains("Example").contains("Wiki");
    }
}
