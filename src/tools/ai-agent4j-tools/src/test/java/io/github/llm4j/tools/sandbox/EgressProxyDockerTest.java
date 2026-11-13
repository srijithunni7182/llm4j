package io.github.llm4j.tools.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.tools.EgressProxy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Phase 35, R11 (the 35d spike) on Docker for Linux: a container on a network with no route out
 * reaches the egress proxy bound on that network's gateway address, and nothing else. Runs only
 * where a Docker daemon answers; the image is {@code alpine:3.20}, pulled once.
 */
@Tag("integration")
class EgressProxyDockerTest {

    static final String IMAGE = "alpine:3.20";
    final String network = "llm4j-egress-test-" + ProcessHandle.current().pid();
    EgressProxy proxy;
    ServerSocket upstream;

    static Result docker(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("docker"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean done = p.waitFor(120, TimeUnit.SECONDS);
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!done) p.destroyForcibly();
        return new Result(done ? p.exitValue() : -1, out);
    }

    record Result(int exit, String out) {}

    @BeforeEach
    void docker() throws Exception {
        Result info;
        try {
            info = docker("info", "--format", "{{.ServerVersion}}");
        } catch (IOException noDocker) {
            info = new Result(-1, "");
        }
        assumeTrue(info.exit() == 0, "no Docker daemon answers here");
        for (int attempt = 0;
                attempt < 4 && docker("image", "inspect", IMAGE).exit() != 0;
                attempt++) {
            if (docker("pull", IMAGE).exit() != 0)
                Thread.sleep(5_000L * (attempt + 1)); // a 429 from the registry
        }
        assumeTrue(docker("image", "inspect", IMAGE).exit() == 0, "could not pull " + IMAGE);
        assertThat(docker("network", "create", "--internal", network).exit()).isZero();
    }

    @AfterEach
    void clean() throws Exception {
        if (proxy != null) proxy.close();
        if (upstream != null) upstream.close();
        docker("network", "rm", network);
    }

    String gateway() throws Exception {
        return docker("network", "inspect", "-f", "{{(index .IPAM.Config 0).Gateway}}", network)
                .out();
    }

    /** Runs {@code sh -c script} in a container on the internal network. */
    String inContainer(String script) throws Exception {
        return docker("run", "--rm", "--network", network, IMAGE, "sh", "-c", script).out();
    }

    @Test
    void aContainerWithNoRouteOutReachesOnlyTheProxyAndOnlyListedHostsThroughIt() throws Exception {
        String gateway = gateway();
        upstream = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread answer =
                new Thread(
                        () -> {
                            try {
                                while (true)
                                    try (Socket s = upstream.accept()) {
                                        s.getOutputStream()
                                                .write(
                                                        "tunnel-ok\n"
                                                                .getBytes(StandardCharsets.UTF_8));
                                    }
                            } catch (IOException closed) {
                                // the test is over
                            }
                        });
        answer.setDaemon(true);
        answer.start();
        // the listed name resolves to a public address; the connector stands in for the internet
        proxy =
                EgressProxy.start(
                        InetAddress.getByName(gateway),
                        0,
                        EgressProxy.Hosts.parse("allowed.example"),
                        host -> List.of(InetAddress.getByName("93.184.215.14")),
                        (address, port) ->
                                new Socket(
                                        InetAddress.getLoopbackAddress(), upstream.getLocalPort()));
        String proxyAt = gateway + " " + proxy.port();

        String through =
                inContainer(
                        "printf 'CONNECT allowed.example:443 HTTP/1.1\\r\\n\\r\\n' | nc -w 5 "
                                + proxyAt);
        assertThat(through).contains("200 Connection Established").contains("tunnel-ok");

        String refused =
                inContainer(
                        "printf 'CONNECT other.example:443 HTTP/1.1\\r\\n\\r\\n' | nc -w 5 "
                                + proxyAt);
        assertThat(refused).contains("403").doesNotContain("tunnel-ok");

        String direct =
                inContainer("nc -w 3 93.184.215.14 443 </dev/null && echo reached || echo blocked");
        assertThat(direct).contains("blocked").doesNotContain("reached");
        String routes = inContainer("ip route");
        assertThat(routes).doesNotContain("default");
    }
}
