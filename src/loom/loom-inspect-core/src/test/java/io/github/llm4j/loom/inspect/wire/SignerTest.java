package io.github.llm4j.loom.inspect.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SignerTest {

    private static final Instant NOW = Instant.parse("2026-10-11T14:00:00Z");
    private static final byte[] BODY = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

    @Test
    void aSignedBodyVerifiesWithTheSameKeyInsideTheWindow() {
        String h = Signer.sign("key-1", NOW, BODY);
        assertThat(Signer.verify("key-1", h, BODY, NOW.plusSeconds(60))).isTrue();
    }

    @Test
    void aDifferentKeyABodyChangeOrAnOldTimestampAllFail() {
        String h = Signer.sign("key-1", NOW, BODY);
        assertThat(Signer.verify("key-2", h, BODY, NOW)).isFalse();
        assertThat(Signer.verify("key-1", h, "{\"a\":2}".getBytes(StandardCharsets.UTF_8), NOW)).isFalse();
        assertThat(Signer.verify("key-1", h, BODY, NOW.plusSeconds(6 * 60))).isFalse(); // replay
        assertThat(Signer.verify("key-1", h, BODY, NOW.minusSeconds(6 * 60))).isFalse();
    }

    @Test
    void garbageHeadersAreRefusedNotThrown() {
        assertThat(Signer.verify("k", null, BODY, NOW)).isFalse();
        assertThat(Signer.verify(null, "t=1,v1=00", BODY, NOW)).isFalse();
        assertThat(Signer.verify("k", "nonsense", BODY, NOW)).isFalse();
        assertThat(Signer.verify("k", "t=abc,v1=zz", BODY, NOW)).isFalse();
        assertThat(Signer.verify("k", "t=" + NOW.getEpochSecond() + ",v1=nothex", BODY, NOW)).isFalse();
    }
}
