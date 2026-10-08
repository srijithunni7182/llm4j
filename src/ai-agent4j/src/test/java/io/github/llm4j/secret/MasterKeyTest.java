package io.github.llm4j.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MasterKeyTest {

    @TempDir
    Path dir;

    @Test
    void passphraseIsCopiedAndWipedOnDestroy() {
        char[] mine = "pass phrase".toCharArray();
        MasterKey k = MasterKey.of(mine);
        java.util.Arrays.fill(mine, '\0');
        assertEquals("pass phrase", new String(k.passphrase()), "the key kept its own copy");
        k.destroy();
        assertThrows(IllegalStateException.class, k::passphrase);
    }

    @Test
    void toStringNeverShowsTheKey() {
        assertFalse(MasterKey.of("hunter2-test".toCharArray()).toString().contains("hunter2"));
        assertEquals("MasterKey[passphrase]", MasterKey.of("x".toCharArray()).toString());
    }

    @Test
    void emptyPassphraseIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> MasterKey.of(new char[0]));
        assertThrows(NullPointerException.class, () -> MasterKey.of(null));
    }

    @Test
    void fromFileTakesTheFirstLineAndIsReadEachTime() throws Exception {
        Path f = dir.resolve("master.key");
        Files.writeString(f, "first line\nsecond line\n", StandardCharsets.UTF_8);
        MasterKey k = MasterKey.fromFile(f);
        assertEquals("first line", new String(k.passphrase()));
        Files.writeString(f, "rotated\r\n", StandardCharsets.UTF_8);
        assertEquals("rotated", new String(k.passphrase()), "a rotated key file is honoured");
        Files.writeString(f, "\nnothing before the newline");
        assertThrows(SecretStoreException.class, k::passphrase);
        Files.delete(f);
        SecretStoreException e = assertThrows(SecretStoreException.class, k::passphrase);
        assertTrue(e.getMessage().contains("master.key"));
    }

    @Test
    void fromEnvUsesTheVariableTheConsumerNames() {
        MasterKey k = MasterKey.fromEnv("MY_APP_VAULT_PASSPHRASE", Map.of("MY_APP_VAULT_PASSPHRASE", "from-env")::get);
        assertEquals("from-env", new String(k.passphrase()));
        assertTrue(k.toString().contains("MY_APP_VAULT_PASSPHRASE"));
        MasterKey unset = MasterKey.fromEnv("NOT_SET", name -> null);
        SecretStoreException e = assertThrows(SecretStoreException.class, unset::passphrase);
        assertTrue(e.getMessage().contains("NOT_SET"));
    }

    @Test
    void supplierSourceIsCalledEachTime() {
        int[] calls = {0};
        MasterKey k = MasterKey.from(() -> {
            calls[0]++;
            return ("p" + calls[0]).toCharArray();
        });
        assertEquals("p1", new String(k.passphrase()));
        assertEquals("p2", new String(k.passphrase()));
        assertThrows(SecretStoreException.class, MasterKey.from(() -> new char[0])::passphrase);
        assertThrows(SecretStoreException.class, MasterKey.from(() -> null)::passphrase);
    }
}
