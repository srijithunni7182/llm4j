package io.github.llm4j.tools.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.tools.EgressProxy;
import io.github.llm4j.tools.NetPolicy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 35, D6 and G48: the egress proxy a container reaches the network through. It lets through
 * only the listed hosts and ports, refuses loopback, private and metadata addresses even for a
 * listed name, and connects to the address it resolved, once.
 */
class EgressProxyTest {

    final List<InetSocketAddress> connected = new ArrayList<>();
    EgressProxy proxy;
    ServerSocket upstream;

    @AfterEach
    void close() throws Exception {
        if (proxy != null) proxy.close();
        if (upstream != null) upstream.close();
    }

    static InetAddress ip(String a) throws java.net.UnknownHostException {
        return InetAddress.getByName(a);
    }

    /** An upstream on loopback that answers "hello"; the connector sends every connection to it. */
    EgressProxy.Connector toUpstream() throws Exception {
        upstream = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread t =
                new Thread(
                        () -> {
                            try {
                                while (true) {
                                    try (Socket s = upstream.accept()) {
                                        s.getOutputStream()
                                                .write("hello\n".getBytes(StandardCharsets.UTF_8));
                                        s.getOutputStream().flush();
                                    }
                                }
                            } catch (Exception closed) {
                                // the test is over
                            }
                        });
        t.setDaemon(true);
        t.start();
        return (address, port) -> {
            connected.add(new InetSocketAddress(address, port));
            return new Socket(InetAddress.getLoopbackAddress(), upstream.getLocalPort());
        };
    }

    EgressProxy start(String hosts, NetPolicy.Resolver resolver) throws Exception {
        proxy =
                EgressProxy.start(
                        InetAddress.getLoopbackAddress(),
                        0,
                        EgressProxy.Hosts.parse(hosts),
                        resolver,
                        toUpstream());
        return proxy;
    }

    /** Sends a CONNECT and returns the status line, then what came through the tunnel. */
    String connect(String target) throws Exception {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
            OutputStream out = s.getOutputStream();
            out.write(
                    ("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in =
                    new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            String status = in.readLine();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {}
            String body = status != null && status.contains(" 200 ") ? in.readLine() : "";
            return status + " | " + body;
        }
    }

    @Test
    void aListedHostOnAPublicAddressIsTunnelled() throws Exception {
        start("pypi.org", h -> List.of(ip("151.101.0.223")));
        assertThat(connect("pypi.org:443")).startsWith("HTTP/1.1 200").endsWith("hello");
        assertThat(connected).containsExactly(new InetSocketAddress(ip("151.101.0.223"), 443));
    }

    @Test
    void aHostOrPortNotOnTheListIsRefused() throws Exception {
        start("pypi.org, files.pythonhosted.org:8443", h -> List.of(ip("151.101.0.223")));
        assertThat(connect("evil.example:443")).startsWith("HTTP/1.1 403");
        assertThat(connect("pypi.org:22")).startsWith("HTTP/1.1 403");
        assertThat(connect("files.pythonhosted.org:443")).startsWith("HTTP/1.1 403");
        assertThat(connect("files.pythonhosted.org:8443")).startsWith("HTTP/1.1 200");
        assertThat(connected).hasSize(1);
    }

    @Test
    void aListedNameThatResolvesToAPrivateOrMetadataAddressIsRefused() throws Exception {
        for (String inside :
                List.of(
                        "10.0.0.5",
                        "127.0.0.1",
                        "169.254.169.254",
                        "192.168.1.1",
                        "172.18.0.1",
                        "::1")) {
            connected.clear();
            start("internal.example", h -> List.of(ip(inside)));
            assertThat(connect("internal.example:443")).as(inside).startsWith("HTTP/1.1 403");
            assertThat(connected).as(inside).isEmpty();
            close();
        }
    }

    @Test
    void anAddressGivenDirectlyIsRefusedEvenWhenItIsPublic() throws Exception {
        start("pypi.org", h -> List.of(ip("151.101.0.223")));
        assertThat(connect("151.101.0.223:443")).startsWith("HTTP/1.1 403");
        assertThat(connected).isEmpty();
    }

    @Test
    void theNameIsResolvedOnceAndThatAddressIsTheOneUsed() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        start(
                "pypi.org",
                h ->
                        asked.getAndIncrement() == 0
                                ? List.of(ip("151.101.0.223"))
                                : List.of(ip("127.0.0.1")));
        assertThat(connect("pypi.org:443")).startsWith("HTTP/1.1 200");
        assertThat(asked.get()).isEqualTo(1);
        assertThat(connected).containsExactly(new InetSocketAddress(ip("151.101.0.223"), 443));
    }

    @Test
    void anythingButConnectIsRefused() throws Exception {
        start("pypi.org", h -> List.of(ip("151.101.0.223")));
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
            s.getOutputStream()
                    .write(
                            "GET http://pypi.org/ HTTP/1.1\r\nHost: pypi.org\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
            String status =
                    new BufferedReader(
                                    new InputStreamReader(
                                            s.getInputStream(), StandardCharsets.US_ASCII))
                            .readLine();
            assertThat(status).startsWith("HTTP/1.1 405");
        }
        assertThat(connected).isEmpty();
    }

    @Test
    void wildcardsMatchSubdomainsOnly() {
        EgressProxy.Hosts hosts = EgressProxy.Hosts.parse("*.pythonhosted.org, example.com:8080");
        assertThat(hosts.allows("files.pythonhosted.org", 443)).isTrue();
        assertThat(hosts.allows("pythonhosted.org", 443)).isFalse();
        assertThat(hosts.allows(".pythonhosted.org", 443)).isFalse();
        assertThat(hosts.allows("a.pythonhosted.org", 443)).isTrue();
        assertThat(hosts.allows("example.com", 8080)).isTrue();
        assertThat(hosts.allows("example.com", 443)).isFalse();
        assertThat(hosts.allows("EXAMPLE.COM", 8080)).isTrue();
        assertThat(hosts.describe())
                .contains("*.pythonhosted.org:443")
                .contains("example.com:8080");
    }

    @Test
    void aBadListIsRefused() {
        for (String bad : List.of("", "a b", "host:99999", "http://x.com", "*", "10.0.0.1"))
            assertThat(EgressProxy.Hosts.problem(bad)).as(bad).isNotNull();
        assertThat(EgressProxy.Hosts.problem("pypi.org, files.pythonhosted.org:443")).isNull();
        assertThat(EgressProxy.Hosts.problem("a.org:1, b.org:65535")).isNull();
        assertThat(EgressProxy.Hosts.problem("a.org:0")).contains("outside 1 to 65535");
        assertThat(EgressProxy.Hosts.problem("a.org:65536")).contains("outside 1 to 65535");
        assertThat(EgressProxy.Hosts.problem("a.org:http")).contains("is not host or host:port");
        assertThat(EgressProxy.Hosts.problem("10.0.0.1")).contains("is not a host name");
    }
}
