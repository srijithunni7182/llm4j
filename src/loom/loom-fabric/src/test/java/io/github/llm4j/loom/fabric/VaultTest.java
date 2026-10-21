package io.github.llm4j.loom.fabric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VaultTest {

    private static final String MASTER = "a-long-enough-master-key";

    @TempDir Path dir;

    @Test
    void valuesAreStoredEncryptedAndReadBackAfterReopening() throws Exception {
        Path f = dir.resolve("vault.json");
        Vault v = Vault.create(f, MASTER);
        v.put("client.getviral", "lfk_secret-value");
        v.put(Vault.ADMIN, "hunter2hunter2");
        assertThat(Files.readString(f))
                .doesNotContain("lfk_secret-value")
                .doesNotContain("hunter2");
        Vault again = Vault.open(f, MASTER);
        assertThat(again.get("client.getviral")).isEqualTo("lfk_secret-value");
        assertThat(again.names()).containsExactly("admin.password", "client.getviral");
        assertThat(again.get("client.none")).isNull();
    }

    @Test
    void aWrongMasterKeyCannotReadAnything_andDoesNotLeakTheValue() {
        Path f = dir.resolve("vault.json");
        Vault.create(f, MASTER).put("client.a", "lfk_x");
        assertThatThrownBy(() -> Vault.open(f, "another-master-key-value").get("client.a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrong master key")
                .hasMessageNotContaining("lfk_x");
    }

    @Test
    void aValueCannotBeMovedToAnotherName() throws Exception {
        Path f = dir.resolve("vault.json");
        Vault v = Vault.create(f, MASTER);
        v.put("client.a", "lfk_a");
        String text = Files.readString(f).replace("\"client.a\"", "\"client.b\"");
        Files.writeString(f, text);
        assertThatThrownBy(() -> Vault.open(f, MASTER).get("client.b"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void removeForgetsAKey_andAnExistingVaultIsNotOverwritten() {
        Path f = dir.resolve("vault.json");
        Vault v = Vault.create(f, MASTER);
        v.put("client.a", "lfk_a");
        assertThat(v.remove("client.a")).isTrue();
        assertThat(Vault.open(f, MASTER).get("client.a")).isNull();
        assertThatThrownBy(() -> Vault.create(f, MASTER)).hasMessageContaining("already");
    }

    @Test
    void aShortMasterKeyIsRefused_generatedKeysAreDistinct_andToStringHidesValues() {
        assertThatThrownBy(() -> Vault.create(dir.resolve("v.json"), "short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Vault.newClientKey()).startsWith("lfk_").isNotEqualTo(Vault.newClientKey());
        Vault v = Vault.create(dir.resolve("t.json"), MASTER);
        v.put("client.z", "lfk_zzz");
        assertThat(v.toString()).doesNotContain("lfk_");
    }

    @Test
    void aChangeMadeByAnotherProcessIsSeenWithoutARestart_soARevokedKeyStopsWorking(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path file = dir.resolve("vault.json");
        Vault server = Vault.create(file, "a-long-enough-master-key");
        server.put("client.alpha", "key-one");
        Vault cli =
                Vault.open(
                        file,
                        "a-long-enough-master-key"); // the 'fabric clients add' command, a separate
        // process
        cli.put("client.beta", "key-two");
        Thread.sleep(1100); // the server looks at most once a second
        assertThat(server.get("client.beta")).isEqualTo("key-two");
        cli.remove("client.alpha");
        Thread.sleep(1100);
        assertThat(server.get("client.alpha")).isNull();
        assertThat(server.names()).containsExactly("client.beta");
    }
}
