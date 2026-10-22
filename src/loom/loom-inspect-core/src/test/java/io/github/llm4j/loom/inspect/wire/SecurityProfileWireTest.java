package io.github.llm4j.loom.inspect.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R32: what each agent can reach travels in a snapshot, and a snapshot from before it reads as it
 * did.
 */
class SecurityProfileWireTest {

    @Test
    void theProfileSurvivesARoundTrip() {
        List<Map<String, Object>> profile =
                List.of(
                        Map.of(
                                "agent",
                                "Billing",
                                "untrusted",
                                List.of("Fetch"),
                                "private",
                                List.of("DB"),
                                "outward",
                                List.of("Refund")));
        Snapshot in =
                new Snapshot(
                        "p",
                        "i_1",
                        "t".repeat(32),
                        "r",
                        "W",
                        Map.of(),
                        List.of(),
                        Map.of(),
                        Map.of("Billing", "m"),
                        profile);
        Snapshot back = Wire.read(Wire.write(in), Snapshot.class);
        assertThat(back.security()).isEqualTo(profile);
        assertThat(back).isEqualTo(in);
    }

    @Test
    void aSnapshotFromBeforeTheProfileStillReads() {
        String old =
                "{\"client\":\"p\",\"instance\":\"i\",\"traceId\":\"t\",\"runId\":\"r\",\"workflow\":\"W\",\"graph\":{},\"ladders\":[],\"inspect\":{},\"models\":{}}";
        assertThat(Wire.read(old, Snapshot.class).security()).isEmpty();
    }

    @Test
    void theOlderConstructorsStillWork() {
        assertThat(new Snapshot("p", "i", "t", "r", "W", Map.of(), List.of()).security()).isEmpty();
        assertThat(
                        new Snapshot(
                                        "p", "i", "t", "r", "W", Map.of(), List.of(), Map.of(),
                                        Map.of())
                                .security())
                .isEmpty();
    }
}
