package io.github.llm4j.spring;

import static io.github.llm4j.spring.ClientsTest.rootConfigurationProblem;
import static io.github.llm4j.spring.Fixtures.CANARY;
import static io.github.llm4j.spring.Fixtures.ask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.secret.EncryptedFileSecretStore;
import io.github.llm4j.secret.InMemorySecretStore;
import io.github.llm4j.secret.MasterKey;
import io.github.llm4j.secret.SecretMetadata;
import io.github.llm4j.secret.SecretStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Where keys come from, and that they go nowhere else. V2. */
@ExtendWith(OutputCaptureExtension.class)
class SecretsTest {

    @TempDir
    Path dir;

    @Tag("SB-V2.1")
    @Test
    void theEnvironmentIsASecretStore() {
        Fixtures.runner().withPropertyValues("llm4j.secrets.store=env", "GEMINI_API_KEY=" + CANARY).run(ctx -> assertThat(ctx.getBean(SecretStore.class).resolve("GEMINI_API_KEY")).isEqualTo(CANARY));
    }

    @Tag("SB-V2.2")
    @Test
    void anEncryptedFileOpensWithTheMasterKeyFromTheEnvironmentOrAFile() throws Exception {
        Path store = dir.resolve("keys.store");
        try (EncryptedFileSecretStore s = EncryptedFileSecretStore.create(store, MasterKey.of("correct horse".toCharArray()))) {
            s.put("API", CANARY);
        }
        Fixtures.runner().withPropertyValues("llm4j.secrets.store=encrypted-file", "llm4j.secrets.file=" + store, "llm4j.secrets.master-key-env=MASTER", "MASTER=correct horse")
                .run(ctx -> assertThat(ctx.getBean(SecretStore.class).resolve("API")).isEqualTo(CANARY));
        Path keyFile = dir.resolve("master.key");
        Files.writeString(keyFile, "correct horse\n");
        Fixtures.runner().withPropertyValues("llm4j.secrets.store=encrypted-file", "llm4j.secrets.file=" + store, "llm4j.secrets.master-key-file=" + keyFile)
                .run(ctx -> assertThat(ctx.getBean(SecretStore.class).resolve("API")).isEqualTo(CANARY));
    }

    @Tag("SB-V2.3")
    @Test
    void theMasterKeyHasExactlyOneSource() {
        Fixtures.runner().withPropertyValues("llm4j.secrets.store=encrypted-file", "llm4j.secrets.file=" + dir.resolve("x"), "llm4j.secrets.master-key-env=A", "llm4j.secrets.master-key-file=/b").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure())).hasMessageContaining("master-key-env").hasMessageContaining("master-key-file");
        });
        Fixtures.runner().withPropertyValues("llm4j.secrets.store=encrypted-file", "llm4j.secrets.file=" + dir.resolve("x")).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure())).hasMessageContaining("llm4j.secrets.master-key-env");
        });
    }

    @Tag("SB-V2.4")
    @Test
    void aChainTriesStoresInOrder() throws Exception {
        Path store = dir.resolve("keys.store");
        try (EncryptedFileSecretStore s = EncryptedFileSecretStore.create(store, MasterKey.of("pw".toCharArray()))) {
            s.put("NAME", "from the file");
        }
        Fixtures.runner().withPropertyValues("llm4j.secrets.store=chained", "llm4j.secrets.chain=encrypted-file,env", "llm4j.secrets.file=" + store, "llm4j.secrets.master-key-env=PW", "PW=pw",
                "NAME=from the environment", "ONLY_ENV=env value").run(ctx -> {
            SecretStore chain = ctx.getBean(SecretStore.class);
            assertThat(chain.resolve("NAME")).isEqualTo("from the file");
            assertThat(chain.resolve("ONLY_ENV")).isEqualTo("env value");
        });
    }

    @Tag("SB-V2.5")
    @Test
    void aStoreTheStarterMadeIsClosedWhenTheApplicationStops() {
        AtomicReference<SecretStore> made = new AtomicReference<>();
        Fixtures.runner().withPropertyValues("llm4j.secrets.store=env", "X=1").run(ctx -> made.set(ctx.getBean(SecretStore.class)));
        // the env store reads the Environment, which is gone with the context; what matters is that the bean is one the container destroys
        assertThat(made.get()).isNotNull();
        Path store = dir.resolve("closing.store");
        AtomicReference<SecretStore> file = new AtomicReference<>();
        Fixtures.runner().withPropertyValues("llm4j.secrets.store=encrypted-file", "llm4j.secrets.file=" + store, "llm4j.secrets.master-key-env=PW", "PW=pw").run(ctx -> {
            file.set(ctx.getBean(SecretStore.class));
            file.get().put("A", "b");
            assertThat(file.get().resolve("A")).isEqualTo("b");
        });
        assertThatThrownBy(() -> file.get().resolve("A")).hasMessageContaining("closed");
    }

    @Tag("SB-V2.6")
    @Test
    void aKeyIsFetchedForEachRequest() throws Exception {
        try (var anthropic = new Fixtures.Provider("anthropic")) {
            InMemorySecretStore store = new InMemorySecretStore();
            store.put("A", "v1");
            Fixtures.runner().withBean(SecretStore.class, () -> store).withPropertyValues("llm4j.clients.default.provider=anthropic", "llm4j.clients.default.api-key-secret=A",
                    "llm4j.clients.default.base-url=" + anthropic.url(), "llm4j.clients.default.model=claude-x", "llm4j.clients.default.retry.enabled=false").run(ctx -> {
                LLMClient client = ctx.getBean(LLMClient.class);
                ask(client, "one");
                store.put("A", "v2");
                ask(client, "two");
                assertThat(anthropic.seen).extracting(r -> r.getHeader("x-api-key")).containsExactly("v1", "v2");
            });
        }
    }

    @Tag("SB-V2.7")
    @Test
    void aKeyBoundToOtherHostsIsNeverSentToThisOne() throws Exception {
        try (var anthropic = new Fixtures.Provider("anthropic")) {
            InMemorySecretStore store = new InMemorySecretStore();
            store.put("A", "v1", SecretMetadata.allowing("api.anthropic.com"));
            Fixtures.runner().withBean(SecretStore.class, () -> store).withPropertyValues("llm4j.clients.default.provider=anthropic", "llm4j.clients.default.api-key-secret=A",
                    "llm4j.clients.default.base-url=" + anthropic.url(), "llm4j.clients.default.model=claude-x", "llm4j.clients.default.retry.enabled=false").run(ctx -> {
                assertThatThrownBy(() -> ask(ctx.getBean(LLMClient.class), "hi")).isInstanceOf(AuthenticationException.class);
                assertThat(anthropic.seen).isEmpty();
            });
        }
    }

    @Tag("SB-V2.8")
    @Test
    void aLiteralAndASecretTogetherAreRefused() {
        Fixtures.runner().withBean(SecretStore.class, InMemorySecretStore::new).withPropertyValues("llm4j.clients.default.provider=anthropic", "llm4j.clients.default.api-key=x",
                "llm4j.clients.default.api-key-secret=A").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure())).hasMessageContaining("llm4j.clients.default.api-key").hasMessageContaining("llm4j.clients.default.api-key-secret");
        });
    }

    @Tag("SB-V2.9")
    @Test
    void aSecretNeedsAStore() {
        Fixtures.runner().withPropertyValues("llm4j.clients.default.provider=anthropic", "llm4j.clients.default.api-key-secret=A").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).action()).contains("llm4j.secrets.store");
        });
    }

    @Tag("SB-V2.10")
    @Test
    void aLiteralKeyIsInNoLogMessageOrPrintout(CapturedOutput output) throws Exception {
        try (var anthropic = new Fixtures.Provider("anthropic")) {
            anthropic.responder = r -> new okhttp3.mockwebserver.MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}");
            Fixtures.runner().withPropertyValues("llm4j.clients.default.provider=anthropic", "llm4j.clients.default.api-key=" + CANARY, "llm4j.clients.default.base-url=" + anthropic.url(),
                    "llm4j.clients.default.model=claude-x", "llm4j.clients.default.retry.enabled=false").run(ctx -> {
                Llm4jProperties properties = ctx.getBean(Llm4jProperties.class);
                assertThat(properties.toString()).doesNotContain(CANARY);
                assertThat(properties.clients().get("default").toString()).doesNotContain(CANARY);
                Throwable failure = catchThrowable(() -> ask(ctx.getBean(LLMClient.class), "hi"));
                assertThat(failure).isNotNull();
                for (Throwable t = failure; t != null; t = t.getCause()) assertThat(String.valueOf(t.getMessage())).doesNotContain(CANARY);
            });
        }
        assertThat(output.getAll()).doesNotContain(CANARY);
    }

    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
