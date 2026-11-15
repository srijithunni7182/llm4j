package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.doctor.Doctor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Phase 36e, D12 (G89): {@code weave doctor --network} reaches the hosts of a script's integrations
 * with a TLS connection and nothing else: no request, no credential, and nothing at all without
 * {@code --network}.
 */
class DoctorIntegrationsTest {

    @TempDir Path dir;

    static final String SCRIPT =
            """
            tool Orders { use: http  base_url: "https://api.orders.example"  auth_header: "Authorization"  auth_value: env.ORDERS_TOKEN }
            tool Pets { use: openapi  spec: "https://specs.pets.example/openapi.json"  operation: "getPet"  effect: reads }
            tool Local { use: openapi  spec: "pets.json"  operation: "getPet"  effect: reads }
            mcp Remote { transport: "http"  url: "https://mcp.tools.example/mcp"  auth: env.ORDERS_TOKEN  list_auth: env.ORDERS_TOKEN }
            workflow Main() { note "x" }
            """;

    private List<String> doctor(String... args) throws Exception {
        Files.writeString(dir.resolve("main.loom"), SCRIPT);
        Files.writeString(dir.resolve("pets.json"), "{}");
        List<String> probed = new ArrayList<>();
        Doctor.Probe probe =
                (host, port, timeout) -> {
                    probed.add(host + ":" + port);
                    return null;
                };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        m -> {
                            throw new IllegalStateException("doctor calls no model");
                        },
                        q -> "no",
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        k -> null);
        DoctorCommand c = new DoctorCommand();
        new CommandLine(c).parseArgs(args);
        DoctorCommand.run(c, env, dir, Map.of("ORDERS_TOKEN", "test-token-000")::get, probe);
        assertThat(out.toString(StandardCharsets.UTF_8)).as("no credential value in the report").doesNotContain("test-token-000");
        return probed;
    }

    @Test
    void networkReachesTheHostOfEachIntegrationWithOneTlsConnectionAndNothingElse() throws Exception {
        assertThat(doctor("--network")).containsExactlyInAnyOrder("api.orders.example:443", "specs.pets.example:443", "mcp.tools.example:443");
    }

    @Test
    void withoutNetworkNothingIsReached() throws Exception {
        assertThat(doctor()).isEmpty();
    }
}
