package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.secret.SecretMetadata;
import io.github.llm4j.secret.SecretNotFoundException;
import io.github.llm4j.secret.SecretStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Chapter 9 says a vault (Google Secret Manager and the like) is reached by a Java host that implements SecretStore and passes it to the
 * executor. This runs that pattern with a stand-in for the vault: the keys come from the host's store, and the script is unchanged.
 */
class VaultHostPatternTest {

    /** The shape of the guide's GoogleSecretStore, over a function instead of a vault client. */
    static final class VaultStore implements SecretStore {
        final Function<String, String> vault;
        int lookups;

        VaultStore(Function<String, String> vault) { this.vault = vault; }

        @Override public String resolve(String name) {
            lookups++;
            String value = vault.apply(name);
            if (value == null) throw new SecretNotFoundException(name);
            return value;
        }
        @Override public boolean contains(String name) { return vault.apply(name) != null; }
        @Override public Set<String> names() { return Set.of(); }
        @Override public Optional<SecretMetadata> metadata(String name) { return Optional.of(SecretMetadata.NONE); }
    }

    private HarnessExecutor executor(Path dir, SecretStore store) throws Exception {
        Path script = Files.writeString(dir.resolve("w.loom"), """
                tool Notify { use: webhook url: secret.SLACK_URL }
                agent A { model: "ollama/llama3" }
                workflow Main() { note "hello" }
                """);
        HarnessExecutor e = new HarnessExecutor(new LoomLoader().load(script.toString()), new io.github.llm4j.loom.execution.ToolRegistry(),
                new DefaultLLMClientFactory(k -> null, store));
        e.setSecretStore(store);
        e.setBaseDir(dir);
        return e;
    }

    @Test
    void aSecretStoreThatTheHostWritesSuppliesTheScriptsSecrets(@TempDir Path dir) throws Exception {
        VaultStore store = new VaultStore(Map.of("SLACK_URL", "https://hooks.slack.com/services/T0/B0/x")::get);
        HarnessExecutor e = executor(dir, store);
        try {
            e.initialize();
            e.executeWorkflow("Main", Map.of());
        } finally {
            e.shutdown();
        }
        assertThat(store.lookups).as("the vault was asked for the secret").isPositive();
    }

    @Test
    void aSecretTheVaultDoesNotHaveIsReportedByName(@TempDir Path dir) throws Exception {
        HarnessExecutor e = executor(dir, new VaultStore(k -> null));
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(e::initialize).hasMessageContaining("SLACK_URL");
        } finally {
            e.shutdown();
        }
    }

    @Test
    void theGuideShowsThePatternItTests() throws Exception {
        String chapter = Files.readString(Path.of("../../docs/guide/09-go-live.md"));
        assertThat(chapter).contains("implements SecretStore").contains("executor.setSecretStore(secrets)").contains("new DefaultLLMClientFactory(System::getenv, secrets)")
                .contains("Google Secret Manager");
        assertThat(Files.readString(Path.of("../../.claude/skills/llm4j-workflow-guide/SKILL.md"))).contains("implements `SecretStore`").contains("executor.setSecretStore(store)");
    }
}
