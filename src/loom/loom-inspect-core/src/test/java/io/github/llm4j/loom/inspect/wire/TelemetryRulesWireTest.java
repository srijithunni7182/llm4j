package io.github.llm4j.loom.inspect.wire;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.state.InspectRules;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R13: the extended rules travel in a snapshot, and a snapshot from before them reads as it did. */
class TelemetryRulesWireTest {

    @Test
    void aSnapshotWithTheNewRulesSurvivesARoundTrip() {
        InspectRules r = new InspectRules(3, null, null, null, null, null, null, null, new InspectRules.Telemetry(0.8, 0.9, 2, 5, 12, 0.6, 20, 3.0));
        Snapshot in = new Snapshot("p", "i_1", "t".repeat(32), "r", "W", Map.of(), java.util.List.of(), Map.of("Editor", r), Map.of("Editor", "m"));
        Snapshot back = Wire.read(Wire.write(in), Snapshot.class);
        assertThat(back.inspect().get("Editor")).isEqualTo(r);
        assertThat(back).isEqualTo(in);
    }

    @Test
    void aSnapshotFromBeforeTheNewRulesStillReads() {
        String old = "{\"client\":\"p\",\"instance\":\"i\",\"traceId\":\"t\",\"runId\":\"r\",\"workflow\":\"W\",\"graph\":{},\"ladders\":[],"
                + "\"inspect\":{\"Editor\":{\"sameAction\":5,\"sameOutput\":null,\"toolFailures\":2,\"ungroundedWarn\":null,\"ungroundedHigh\":null,\"pausedLongerThanSeconds\":null,"
                + "\"noProgressRounds\":3,\"noProgressVariable\":\"review.score\"}},\"models\":{}}";
        Snapshot s = Wire.read(old, Snapshot.class);
        InspectRules r = s.inspect().get("Editor");
        assertThat(r.sameAction()).isEqualTo(5);
        assertThat(r.noProgressVariable()).isEqualTo("review.score");
        assertThat(r.telemetry()).isNull();
    }

    @Test
    void aNewerSenderWithRulesThisVersionDoesNotKnowIsStillRead() {
        String newer = "{\"client\":\"p\",\"instance\":\"i\",\"traceId\":\"t\",\"runId\":\"r\",\"workflow\":\"W\",\"graph\":{},\"ladders\":[],"
                + "\"inspect\":{\"Editor\":{\"sameAction\":5,\"telemetry\":{\"contextWarn\":0.7,\"somethingNext\":1}}},\"models\":{}}";
        Snapshot s = Wire.read(newer, Snapshot.class);
        assertThat(s.inspect().get("Editor").telemetry().contextWarn()).isEqualTo(0.7);
    }
}
