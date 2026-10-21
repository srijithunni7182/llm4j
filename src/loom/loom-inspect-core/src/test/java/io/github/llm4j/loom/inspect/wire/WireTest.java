package io.github.llm4j.loom.inspect.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class WireTest {

    @Test
    void helloSurvivesARoundTrip() {
        Hello in =
                new Hello(
                        "billing-svc",
                        "i_9",
                        "10.0.2.7",
                        988,
                        "5.0",
                        "0.1",
                        Wire.VERSION,
                        List.of(
                                new Hello.Workflow("Support", "ab12"),
                                new Hello.Workflow("RefundTriage", "cd34")));
        assertThat(Wire.read(Wire.write(in), Hello.class)).isEqualTo(in);
    }

    @Test
    void aNewerProcessWithExtraFieldsIsStillUnderstood() {
        Hello h =
                Wire.read(
                        "{\"client\":\"p\",\"instance\":\"i\",\"wire\":2,\"somethingNew\":{\"a\":1},\"workflows\":[{\"name\":\"W\",\"scriptHash\":\"h\",\"x\":1}]}",
                        Hello.class);
        assertThat(h.client()).isEqualTo("p");
        assertThat(h.wire()).isEqualTo(2);
        assertThat(h.workflows()).extracting(Hello.Workflow::name).containsExactly("W");
    }

    @Test
    void heartbeatSurvivesARoundTrip() {
        Heartbeat in =
                new Heartbeat(
                        "p",
                        "i",
                        "2026-10-11T14:08:52Z",
                        120,
                        5,
                        2,
                        java.util.Map.of("0123", List.of(java.util.Map.of("name", "Refund"))));
        assertThat(Wire.read(Wire.write(in), Heartbeat.class)).isEqualTo(in);
    }

    @Test
    void anOldHeartbeatWithoutTheNewFieldsStillReads() {
        Heartbeat h =
                Wire.read("{\"client\":\"p\",\"instance\":\"i\",\"at\":\"x\"}", Heartbeat.class);
        assertThat(h.backlog()).isZero();
        assertThat(h.ladders()).isEmpty();
    }

    @Test
    void garbageIsAClearError() {
        assertThatThrownBy(() -> Wire.read("[1,2", Hello.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Hello");
    }

    @Test
    void aSnapshotCarriesTheAgentsInspectRules_andAnOldOneWithoutThemStillReads() {
        Snapshot in =
                new Snapshot(
                        "p",
                        "i",
                        "t",
                        "r",
                        "W",
                        java.util.Map.of(),
                        List.of(),
                        java.util.Map.of(
                                "Editor",
                                new io.github.llm4j.loom.inspect.state.InspectRules(
                                        5, null, null, 1, 3, 90L, 3, "review.score")));
        assertThat(Wire.read(Wire.write(in), Snapshot.class)).isEqualTo(in);
        Snapshot old =
                Wire.read(
                        "{\"client\":\"p\",\"instance\":\"i\",\"traceId\":\"t\"}", Snapshot.class);
        assertThat(old.inspect()).isEmpty();
    }
}
