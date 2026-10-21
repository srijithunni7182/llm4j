package io.github.llm4j.loom.inspect.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The trust a process puts in a server, against a real TLS server with a certificate from {@code
 * Tls.createCa}.
 */
class TlsTest {

    @TempDir static Path dir;

    private static Tls.Created created;
    private static HttpsServer server;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        created = Tls.createCa(dir, "fabric.test", List.of("localhost", "127.0.0.1"));
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(
                new HttpsConfigurator(
                        Tls.serverContext(
                                created.serverKeystore(),
                                Files.readString(created.passwordFile()).toCharArray())));
        server.createContext(
                "/",
                x -> {
                    byte[] b = "ok".getBytes();
                    x.sendResponseHeaders(200, b.length);
                    x.getResponseBody().write(b);
                    x.close();
                });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static String get(javax.net.ssl.SSLContext ctx, String host) throws Exception {
        HttpClient c = HttpClient.newBuilder().sslContext(ctx).build();
        return c.send(
                        HttpRequest.newBuilder(URI.create("https://" + host + ":" + port + "/"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .body();
    }

    @Test
    void aProcessGivenTheCaTrustsTheServer_andOneWithoutItDoesNot() throws Exception {
        assertThat(get(Tls.clientContext(created.caPem(), false, null), "localhost"))
                .isEqualTo("ok");
        assertThat(get(Tls.clientContext(created.caPem(), true, null), "localhost"))
                .isEqualTo("ok");
        assertThatThrownBy(() -> get(Tls.clientContext(null, false, null), "localhost"))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void aPinnedKeyIsEnough_andAWrongPinIsRefused() throws Exception {
        assertThat(get(Tls.clientContext(null, false, created.pin()), "localhost")).isEqualTo("ok");
        assertThatThrownBy(() -> get(Tls.clientContext(null, false, "00".repeat(32)), "localhost"))
                .hasRootCauseMessage("the server's key does not match --observe-pin");
    }

    @Test
    void theHostNameMustBeInTheCertificateEvenWithTheRightCa() {
        // the certificate names fabric.test, localhost and 127.0.0.1; an address it does not name
        // must fail
        assertThatThrownBy(() -> get(Tls.clientContext(created.caPem(), false, null), "127.0.0.2"))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void thePinMatchesWhatIsPrintedForTheServer() {
        assertThat(created.pin()).hasSize(64).matches("[0-9a-f]+");
        assertThat(Tls.pinOfKeystore(created.serverKeystore(), readPassword()))
                .isEqualTo(created.pin());
    }

    @Test
    void creatingOverAnExistingCaIsRefused() {
        assertThatThrownBy(() -> Tls.createCa(dir, "x.test", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already");
    }

    private static char[] readPassword() {
        try {
            return Files.readString(created.passwordFile()).toCharArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aRelativeOutputDirectoryWorksToo() throws Exception {
        Path relative = Path.of("target", "tls-relative-" + System.nanoTime());
        try {
            Tls.Created c = Tls.createCa(relative, "fabric.test", List.of());
            assertThat(Files.exists(c.caPem())).isTrue();
            assertThat(Files.exists(relative.resolve("server.p12"))).isTrue();
        } finally {
            try (var walk = Files.walk(relative.toAbsolutePath())) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
