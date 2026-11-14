package io.github.llm4j.tools.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.tool.Outcome;
import io.github.llm4j.secret.CredentialSource;
import io.github.llm4j.tools.CredentialRejected;
import io.github.llm4j.tools.EffectTool;
import io.github.llm4j.tools.GenericKind;
import io.github.llm4j.tools.GenericTool;
import io.github.llm4j.tools.HttpSupport;
import io.github.llm4j.tools.NetOptions;
import io.github.llm4j.tools.NetPolicy;
import io.github.llm4j.tools.Options;
import io.github.llm4j.tools.Redactor;
import io.github.llm4j.tools.support.FakeEffectful;
import io.github.llm4j.tools.support.RecordingEffects;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;

/**
 * The pieces phase 36 added to this module for the kinds built on it: a typed credential rejection, a
 * trust manager for a private authority (verification stays on), a timeout that says it timed out,
 * and the public network options.
 */
class Phase36FoundationTest {

    final RecordingEffects ctx = new RecordingEffects();

    /** A tool whose call is refused by the credential's owner. */
    private static final class Refused extends GenericTool {
        Refused(RecordingEffects ctx) {
            super("Refused", "test", "d", Redactor.NONE, ctx);
        }

        @Override
        public boolean isEffect(Map<String, Object> args) {
            return true;
        }

        @Override
        protected String run(Map<String, Object> args, String key) {
            throw new CredentialRejected("the server refused it");
        }
    }

    @Test
    void aCredentialRejectionIsATypedOutcomeAndTheAuditSaysSo() {
        Refused tool = new Refused(ctx);

        Outcome outcome = tool.perform(Map.of(), null);

        assertThat(outcome.status()).isEqualTo(Outcome.Status.CREDENTIAL_REJECTED);
        assertThat(outcome.text()).startsWith("Error: credential_rejected: the server refused it");
    }

    @Test
    void aCredentialRejectionInAJournaledEffectIsAFailureThatMayBeCalledAgain() {
        EffectTool tool = new EffectTool(new Refused(ctx), ctx);

        String first = tool.execute(Map.of());
        String second = tool.execute(Map.of());

        assertThat(first).startsWith("Error: credential_rejected:");
        assertThat(second).as("nothing was done, so the next attempt is a fresh call").startsWith("Error: credential_rejected:");
        assertThat(ctx.audit).anySatisfy(e -> assertThat(e.data().toString()).contains("credential_rejected"));
    }

    @Test
    void aKindThatDoesNotTakeInvokerCredentialsRefusesThem() {
        GenericKind kind = new io.github.llm4j.tools.WebhookKind();

        assertThatThrownBy(
                        () ->
                                kind.create(
                                        "Hook",
                                        Map.of("url", "https://hooks.example/x"),
                                        Map.of("secret", CredentialSource.of("abc-123")),
                                        Path.of("."),
                                        ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not take");
    }

    @Test
    void theNetworkOptionsAreAvailableToKindsInOtherModules() {
        NetOptions net =
                NetOptions.parse(
                        new Options(Map.of("timeout", "5s", "retries", "1", "idempotency", "true", "on_unknown", "retry", "allow_http", "true")),
                        2,
                        true);

        assertThat(net.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(net.retries()).isEqualTo(1);
        assertThat(net.policy(0).idempotent()).isTrue();
        assertThat(net.rules().allowHttp()).isTrue();
        assertThat(NetOptions.NAMES).contains("timeout", "retries", "hosts");
    }

    @Test
    void aTimedOutCallSaysTimedOutNotConnectionLost() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            HttpSupport http =
                    new HttpSupport(
                            new NetPolicy(new NetPolicy.Rules(true, false, Set.of(), false), NetPolicy.Resolver.SYSTEM, "localhost"),
                            ctx,
                            Duration.ofMillis(300),
                            1024,
                            0);

            assertThatThrownBy(() -> http.send(new Request.Builder().url(server.url("/")).build(), HttpSupport.Retry.ALL))
                    .hasMessageContaining("timed out");
        }
    }

    @Test
    void aServerBehindAPrivateAuthorityIsReachedOnlyWhenThatAuthorityIsTrusted() throws IOException {
        HeldCertificate authority = new HeldCertificate.Builder().certificateAuthority(0).build();
        HeldCertificate leaf = new HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").signedBy(authority).build();
        HandshakeCertificates serverSide = new HandshakeCertificates.Builder().heldCertificate(leaf).build();
        HandshakeCertificates clientSide = new HandshakeCertificates.Builder().addTrustedCertificate(authority.certificate()).build();
        NetPolicy policy = new NetPolicy(new NetPolicy.Rules(false, false, Set.of(), false), NetPolicy.Resolver.SYSTEM, "localhost");
        try (MockWebServer server = new MockWebServer()) {
            server.useHttps(serverSide.sslSocketFactory(), false);
            server.enqueue(new MockResponse().setBody("hello"));
            server.enqueue(new MockResponse().setBody("hello"));
            server.start();
            Request request = new Request.Builder().url(server.url("/")).build();

            HttpSupport trusting =
                    new HttpSupport(policy, ctx, Duration.ofSeconds(5), 1024, 0, clientSide.sslSocketFactory(), clientSide.trustManager());
            HttpSupport defaults = new HttpSupport(policy, ctx, Duration.ofSeconds(5), 1024, 0);

            assertThat(trusting.send(request, HttpSupport.Retry.NONE).bodyText()).isEqualTo("hello");
            assertThatThrownBy(() -> defaults.send(request, HttpSupport.Retry.NONE)).hasMessageContaining("TLS failed");
        }
    }

    @Test
    void theEffectToolStillTreatsAFailedCallAsRepeatable() {
        FakeEffectful fake = new FakeEffectful("F");
        fake.next = () -> Outcome.credentialRejected("no");
        EffectTool tool = new EffectTool(fake, ctx);

        tool.execute(Map.of());
        tool.execute(Map.of());

        assertThat(fake.performed).hasSize(2);
    }
}
