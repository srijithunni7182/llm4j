package io.github.llm4j.agent.tools.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.secret.CredentialSource;
import io.github.llm4j.secret.InMemorySecretStore;
import io.github.llm4j.secret.SecretException;
import io.github.llm4j.secret.SecretRef;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class RequestAuthTest {

    private static final String HTTPS = "https://api.example.com";

    @Test
    @Tag("CC-V2.1")
    void givenStringsStoredSecretsAndSourcesAreAllPutOnTheRequest() {
        InMemorySecretStore store = new InMemorySecretStore();
        store.put("KEY", "stored-value");
        RequestAuth auth = new RequestAuth()
                .header("X-Static", "static-header")
                .query("static_q", "static-query")
                .header("X-Ref", SecretRef.of(store, "KEY"))
                .query("ref_q", SecretRef.of(store, "KEY"))
                .header("Authorization", CredentialSource.of("Bearer tok-123456"))
                .query("src_q", CredentialSource.of("src-value"));

        RequestAuth.Resolved r = auth.resolve(HTTPS);

        assertThat(r.headers()).containsEntry("X-Static", "static-header").containsEntry("X-Ref", "stored-value")
                .containsEntry("Authorization", "Bearer tok-123456");
        assertThat(r.query()).containsEntry("static_q", "static-query").containsEntry("ref_q", "stored-value").containsEntry("src_q", "src-value");
    }

    @Test
    @Tag("CC-V5.4")
    void everyValueIsListedForScrubbingAndABearerTokenIsListedOnItsOwnToo() {
        RequestAuth auth = new RequestAuth()
                .header("X-Static", "static-header")
                .query("static_q", "static-query")
                .header("Authorization", CredentialSource.of("Bearer tok-123456"))
                .query("src_q", CredentialSource.of("src-value"));

        List<String> secrets = auth.resolve(HTTPS).secrets();

        assertThat(secrets).contains("static-header", "static-query", "Bearer tok-123456", "tok-123456", "src-value");
    }

    @Test
    @Tag("CC-V2.3")
    void aSourceIsAskedOnEveryResolve() {
        List<String> values = new ArrayList<>(List.of("Bearer one", "Bearer two"));
        RequestAuth auth = new RequestAuth().header("Authorization", (CredentialSource) () -> values.remove(0));

        assertThat(auth.resolve(HTTPS).headers()).containsEntry("Authorization", "Bearer one");
        assertThat(auth.resolve(HTTPS).headers()).containsEntry("Authorization", "Bearer two");
    }

    @Test
    @Tag("CC-V2.5")
    void onlySourcesMakeARefusedRequestWorthRepeating() {
        InMemorySecretStore store = new InMemorySecretStore();
        store.put("KEY", "stored-value");
        RequestAuth plain = new RequestAuth().header("A", "b").query("c", "d").header("E", SecretRef.of(store, "KEY"));
        RequestAuth sourced = new RequestAuth().query("k", CredentialSource.of("v"));

        assertThat(plain.resolve(HTTPS).canRetry()).isFalse();
        assertThat(sourced.resolve(HTTPS).canRetry()).isTrue();
    }

    @Test
    @Tag("CC-V2.5")
    void rejectedTellsEverySourceThatWasUsed() {
        List<String> told = new ArrayList<>();
        RequestAuth auth = new RequestAuth()
                .header("A", source("a", told))
                .query("b", source("b", told));

        auth.resolve(HTTPS).rejected();

        assertThat(told).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @Tag("CC-V2.8")
    void aSourceWithNothingToGiveIsReportedByName() {
        RequestAuth nothing = new RequestAuth().header("Authorization", (CredentialSource) () -> null);
        RequestAuth blank = new RequestAuth().header("Authorization", (CredentialSource) () -> "  ");

        assertThatThrownBy(() -> nothing.resolve(HTTPS)).isInstanceOf(SecretException.class)
                .hasMessage("there is no credential for Authorization");
        assertThatThrownBy(() -> blank.resolve(HTTPS)).isInstanceOf(SecretException.class)
                .hasMessage("there is no credential for Authorization");
    }

    @Test
    @Tag("CC-V2.9")
    void aSecretExceptionFromTheHostPassesThroughAsIs() {
        RequestAuth auth = new RequestAuth().header("Authorization", (CredentialSource) () -> {
            throw new SecretException("refresh token revoked");
        });

        assertThatThrownBy(() -> auth.resolve(HTTPS)).isInstanceOf(SecretException.class).hasMessage("refresh token revoked");
    }

    @Test
    @Tag("CC-V5.1")
    void anyOtherFailureIsReportedWithoutItsMessage() {
        RequestAuth auth = new RequestAuth().header("Authorization", (CredentialSource) () -> {
            throw new IllegalStateException("token=SECRETVALUE-9f3a");
        });

        assertThatThrownBy(() -> auth.resolve(HTTPS)).isInstanceOf(SecretException.class)
                .hasMessage("credential for Authorization could not be obtained (IllegalStateException)")
                .hasMessageNotContaining("SECRETVALUE");
    }

    @Test
    @Tag("CC-V4.2")
    void aSourceIsOnlyUsedOverHttpsOrOnThisMachine() {
        RequestAuth auth = new RequestAuth().header("Authorization", CredentialSource.of("Bearer t"));

        assertThat(auth.resolve("https://api.example.com").headers()).isNotEmpty();
        assertThat(auth.resolve("HTTPS://api.example.com").headers()).isNotEmpty();
        assertThat(auth.resolve("http://localhost:8080").headers()).isNotEmpty();
        assertThat(auth.resolve("http://127.0.0.1:8080").headers()).isNotEmpty();
        assertThat(auth.resolve("http://[::1]:8080").headers()).isNotEmpty();
        assertThatThrownBy(() -> auth.resolve("http://api.example.com")).isInstanceOf(SecretException.class)
                .hasMessageContaining("only sent over https").hasMessageContaining("api.example.com");
        assertThatThrownBy(() -> auth.resolve("http://localhost.evil.test")).isInstanceOf(SecretException.class);
        assertThatThrownBy(() -> auth.resolve("")).isInstanceOf(SecretException.class).hasMessageContaining("unknown host");
        assertThatThrownBy(() -> auth.resolve(null)).isInstanceOf(SecretException.class);
    }

    @Test
    @Tag("CC-V4.2")
    void theHttpsRuleDoesNotChangeHowStringsAndStoredSecretsWork() {
        InMemorySecretStore store = new InMemorySecretStore();
        store.put("KEY", "stored-value");
        RequestAuth auth = new RequestAuth().header("A", "b").query("c", SecretRef.of(store, "KEY"));

        assertThat(auth.resolve("http://api.example.com").query()).containsEntry("c", "stored-value");
    }

    @Test
    @Tag("CC-V5.4")
    void aValueThatStartsWithASpaceHasNoSeparateTokenPart() {
        RequestAuth auth = new RequestAuth().header("Authorization", CredentialSource.of(" abcdefgh"));

        assertThat(auth.resolve(HTTPS).secrets()).containsExactly(" abcdefgh");
    }

    private static CredentialSource source(String id, List<String> told) {
        return new CredentialSource() {
            @Override
            public String get() {
                return "value-" + id;
            }

            @Override
            public void rejected() {
                told.add(id);
            }
        };
    }
}
