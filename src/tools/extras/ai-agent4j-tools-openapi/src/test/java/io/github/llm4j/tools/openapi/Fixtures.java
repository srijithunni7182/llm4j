package io.github.llm4j.tools.openapi;

import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.EffectJournal;
import io.github.llm4j.ratelimit.Sleeper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/**
 * What the openapi tests share: a real HTTPS server (MockWebServer on a socket) whose certificate
 * is signed by a test authority the client trusts, so a real TLS handshake runs with certificate and
 * host-name checks on (D15, rule 1), the recorded Petstore fixtures, and an effect context that
 * waits instantly and records what it was told.
 */
final class Fixtures {

    private Fixtures() {}

    /** An HTTPS server for {@code localhost} and the kind that trusts its authority. */
    static final class Tls implements AutoCloseable {
        final MockWebServer server = new MockWebServer();
        final OpenApiKind kind;

        Tls() throws IOException {
            HeldCertificate authority = new HeldCertificate.Builder().certificateAuthority(0).build();
            HeldCertificate leaf =
                    new HeldCertificate.Builder()
                            .commonName("localhost")
                            .addSubjectAlternativeName("localhost")
                            .signedBy(authority)
                            .build();
            HandshakeCertificates serverSide =
                    new HandshakeCertificates.Builder().heldCertificate(leaf).build();
            HandshakeCertificates clientSide =
                    new HandshakeCertificates.Builder()
                            .addTrustedCertificate(authority.certificate())
                            .build();
            server.useHttps(serverSide.sslSocketFactory(), false);
            server.start();
            kind = new OpenApiKind(clientSide.sslSocketFactory(), clientSide.trustManager());
        }

        /** The next request the server got; fails the test instead of waiting forever when none came. */
        okhttp3.mockwebserver.RecordedRequest next() throws InterruptedException {
            okhttp3.mockwebserver.RecordedRequest request =
                    server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
            if (request == null) throw new AssertionError("the server was sent no request");
            return request;
        }

        String url(String path) {
            return "https://localhost:" + server.getPort() + path;
        }

        @Override
        public void close() throws IOException {
            server.shutdown();
        }
    }

    /** The options of a declaration, in the order written. */
    static Map<String, String> options(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return out;
    }

    /** A recorded Petstore fixture, copied into {@code dir}. */
    static Path fixture(Path dir, String name, String as) throws IOException {
        try (InputStream in =
                Fixtures.class.getResourceAsStream("/fixtures/petstore3/" + name)) {
            if (in == null) throw new IllegalStateException("no fixture " + name);
            Path target = dir.resolve(as);
            Files.write(target, in.readAllBytes());
            return target;
        }
    }

    static String text(String fixtureName) throws IOException {
        try (InputStream in =
                Fixtures.class.getResourceAsStream("/fixtures/petstore3/" + fixtureName)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Waits instantly, keeps time still, and records what tools report. */
    static final class Context implements EffectContext {
        final List<String> audit = Collections.synchronizedList(new ArrayList<>());
        final List<String> trace = Collections.synchronizedList(new ArrayList<>());
        final List<Duration> slept = Collections.synchronizedList(new ArrayList<>());
        private final EffectJournal journal = EffectJournal.inMemory();
        private final Clock clock =
                Clock.fixed(Instant.parse("2026-10-01T09:00:00Z"), ZoneOffset.UTC);

        @Override
        public void audit(String event, Map<String, Object> data) {
            audit.add(event + " " + data);
        }

        @Override
        public void trace(String text, Map<String, Object> data) {
            trace.add(text + " " + data);
        }

        @Override
        public EffectJournal journal() {
            return journal;
        }

        @Override
        public String currentStep() {
            return "main/s0";
        }

        @Override
        public long attempt() {
            return 1;
        }

        @Override
        public Set<Path> reservedPaths() {
            return Set.of();
        }

        @Override
        public Sleeper sleeper() {
            return slept::add;
        }

        @Override
        public Clock clock() {
            return clock;
        }

        String everythingSaid() {
            return String.join("\n", audit) + "\n" + String.join("\n", trace);
        }
    }
}
