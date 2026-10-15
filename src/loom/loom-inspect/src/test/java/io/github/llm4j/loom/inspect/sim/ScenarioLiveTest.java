package io.github.llm4j.loom.inspect.sim;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.fabric.FabricServer;
import io.github.llm4j.loom.fabric.Vault;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R22: the scenarios run against a real Fabric server through the real add-on, selected by name, and show up there. */
class ScenarioLiveTest {

    @TempDir
    Path dir;

    private FabricServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    @SuppressWarnings("unchecked")
    @Test
    void scenariosSelectedByNameReachFabricWithTheirIncidents() throws Exception {
        Vault vault = Vault.create(dir.resolve("vault.json"), "a-long-enough-master-key");
        String key = Vault.newClientKey();
        vault.put("client.sim-1", key);
        vault.put(Vault.ADMIN, "correct horse battery");
        server = new FabricServer(new FabricServer.Settings(dir.resolve("data"), "127.0.0.1", 0, null, false, 30, 1L << 30), vault, new PrintStream(new ByteArrayOutputStream()));
        server.start();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        int code = new Simulate.Simulation("http://127.0.0.1:" + server.port(), "sim-1", "UNUSED", null, null, false, 0, 1, 0, 0.01, "x", "truncated",
                dir.resolve("outbox").toString(), null, new PrintStream(log)).withKey(key).withScenarios("task-broken,trifecta").run();
        assertThat(code).isZero();
        assertThat(log.toString()).contains("scenario task-broken").contains("scenario trifecta");

        long until = System.currentTimeMillis() + 15_000;
        boolean broken = false;
        boolean trifecta = false;
        while (System.currentTimeMillis() < until && !(broken && trifecta)) {
            for (Map<String, Object> summary : server.registry().summaries()) {
                var run = server.registry().find("sim-1", (String) summary.get("traceId")).orElseThrow();
                for (Map<String, Object> i : (List<Map<String, Object>>) run.view().get("incidents")) {
                    if ("BROKEN".equals(i.get("kind"))) broken = true;
                    if ("TRIFECTA".equals(i.get("kind"))) trifecta = true;
                }
            }
            Thread.sleep(50);
        }
        StringBuilder seen = new StringBuilder();
        for (Map<String, Object> summary : server.registry().summaries()) {
            var run = server.registry().find("sim-1", (String) summary.get("traceId")).orElseThrow();
            seen.append(run.view().get("status")).append(" ").append(run.view().get("incidents")).append(" | ");
        }
        assertThat(broken).as("the broken task reached Fabric as a BROKEN incident; seen: " + seen + " log: " + log).isTrue();
        assertThat(trifecta).as("the trifecta reached Fabric").isTrue();
    }

    @Test
    void anUnknownScenarioNameIsRefusedBeforeAnythingStarts() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Simulate.Simulation(null, "x", "K", null, null, true, 0, 1, 0, 1.0, "t", "truncated", "o", null, System.out).withScenarios("flaky-tool,nope"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
    }
}
