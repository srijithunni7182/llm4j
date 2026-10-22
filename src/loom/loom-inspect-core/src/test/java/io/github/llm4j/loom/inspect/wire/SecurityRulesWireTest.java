package io.github.llm4j.loom.inspect.wire;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.state.InspectRules;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R38: the security rules travel in a snapshot, and a snapshot from before them reads as it did.
 */
class SecurityRulesWireTest {

    @Test
    void aSnapshotWithTheSecurityRulesSurvivesARoundTrip() {
        InspectRules r =
                new InspectRules(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new InspectRules.Security(5, 120L, false, "reads tickets"));
        Snapshot in =
                new Snapshot(
                        "p",
                        "i_1",
                        "t".repeat(32),
                        "r",
                        "W",
                        Map.of(),
                        java.util.List.of(),
                        Map.of("Support", r),
                        Map.of("Support", "m"));
        Snapshot back = Wire.read(Wire.write(in), Snapshot.class);
        assertThat(back.inspect().get("Support")).isEqualTo(r);
        assertThat(back).isEqualTo(in);
    }

    @Test
    void aSnapshotFromBeforeTheSecurityRulesStillReads() {
        String old =
                "{\"client\":\"p\",\"instance\":\"i\",\"traceId\":\"t\",\"runId\":\"r\",\"workflow\":\"W\",\"graph\":{},\"ladders\":[],"
                        + "\"inspect\":{\"Editor\":{\"sameAction\":5,\"telemetry\":{\"contextWarn\":0.7}}},\"models\":{}}";
        InspectRules r = Wire.read(old, Snapshot.class).inspect().get("Editor");
        assertThat(r.sameAction()).isEqualTo(5);
        assertThat(r.security()).isNull();
    }

    @Test
    void aNewerSenderWithAFieldThisVersionDoesNotKnowIsStillRead() {
        String newer =
                "{\"client\":\"p\",\"instance\":\"i\",\"traceId\":\"t\",\"runId\":\"r\",\"workflow\":\"W\",\"graph\":{},\"ladders\":[],"
                        + "\"inspect\":{\"Editor\":{\"security\":{\"injectionScan\":false,\"somethingNext\":1}}},\"models\":{}}";
        assertThat(
                        Wire.read(newer, Snapshot.class)
                                .inspect()
                                .get("Editor")
                                .security()
                                .injectionScan())
                .isFalse();
    }
}
