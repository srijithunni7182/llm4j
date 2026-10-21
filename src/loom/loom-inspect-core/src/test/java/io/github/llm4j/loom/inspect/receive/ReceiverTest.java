package io.github.llm4j.loom.inspect.receive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.inspect.wire.Heartbeat;
import io.github.llm4j.loom.inspect.wire.Hello;
import io.github.llm4j.loom.inspect.wire.Snapshot;
import io.github.llm4j.loom.inspect.wire.Wire;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReceiverTest {

    private static final String RUN = "r-1";
    private static final String TRACE = Ids.traceId(RUN);

    private static final class Manual extends Clock {
        Instant now = Instant.parse("2026-10-11T14:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final Manual clock = new Manual();
    private final RunRegistry registry = new RunRegistry();
    private final Receiver receiver = new Receiver(registry, clock);

    private static Hello hello(String name, int wire) {
        return new Hello(
                name,
                "i_1",
                "host-1",
                42,
                "5.0",
                "0.1",
                wire,
                List.of(new Hello.Workflow("Main", "h")));
    }

    private static String logs(String service, long seq, String event) {
        LogRec r =
                new LogRec(
                        1_000_000_000L * (1 + seq),
                        LogRec.INFO,
                        "w",
                        Map.of(
                                Semconv.EVENT_NAME,
                                event,
                                Semconv.LOOM_SEQ,
                                seq,
                                Semconv.LOOM_RUN_ID,
                                RUN,
                                Semconv.LOOM_WORKFLOW,
                                "Main"),
                        TRACE,
                        null);
        return OtlpJson.encodeLogs(Resource.of(service, "i_1"), "0.1", List.of(r));
    }

    private Map<String, Object> process(String name) {
        return receiver.processes().stream()
                .filter(p -> name.equals(p.get("name")))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aHelloMakesAProcessOnline_andItGoesOfflineWhenItsMessagesStop() {
        Receiver.HelloReply reply = receiver.hello("getviral", hello("getviral", Wire.VERSION));
        assertThat(reply.supported()).isTrue();
        assertThat(process("getviral"))
                .containsEntry("state", "online")
                .containsEntry("host", "host-1")
                .containsEntry("workflows", List.of("Main"));
        clock.now = clock.now.plus(Duration.ofSeconds(14));
        assertThat(process("getviral")).containsEntry("state", "online");
        clock.now = clock.now.plus(Duration.ofSeconds(2));
        assertThat(process("getviral")).containsEntry("state", "offline");
        receiver.heartbeat("getviral", new Heartbeat("getviral", "i_1", "x"));
        assertThat(process("getviral")).containsEntry("state", "online");
    }

    @Test
    void aNewerWireVersionIsAcceptedButShownAsUnsupported() {
        Receiver.HelloReply reply = receiver.hello("p", hello("p", Wire.VERSION + 1));
        assertThat(reply.supported()).isFalse();
        assertThat(process("p")).containsEntry("state", "unsupported");
    }

    @Test
    void aClientCannotReportAsAnotherClient() {
        assertThatThrownBy(() -> receiver.hello("a", hello("b", 1)))
                .isInstanceOf(Receiver.Rejected.class);
        assertThatThrownBy(() -> receiver.logs("a", logs("b", 1, Semconv.EV_RUN_START)))
                .isInstanceOf(Receiver.Rejected.class);
        assertThatThrownBy(() -> receiver.heartbeat("a", new Heartbeat("b", "i", "x")))
                .isInstanceOf(Receiver.Rejected.class);
        assertThat(registry.summaries()).isEmpty();
    }

    @Test
    void logsReachTheRegistry_andAReplayedBatchIsNotCountedTwice() {
        receiver.hello("p", hello("p", 1));
        receiver.logs("p", logs("p", 1, Semconv.EV_RUN_START));
        receiver.logs(
                "p", logs("p", 1, Semconv.EV_RUN_START)); // sent again after a lost acknowledgement
        Map<String, Object> run = registry.findByTrace(TRACE).orElseThrow().view();
        assertThat(run)
                .containsEntry("status", "running")
                .containsEntry("workflow", "Main")
                .containsEntry("client", "p");
        assertThat(run.get("gaps")).isEqualTo(0L);
    }

    @Test
    void aSnapshotGivesTheRunItsGraphAndLadders_andAHeartbeatCarriesTheLatestLadders() {
        receiver.hello("p", hello("p", 1));
        receiver.logs("p", logs("p", 1, Semconv.EV_RUN_START));
        receiver.snapshot(
                "p",
                new Snapshot(
                        "p",
                        "i_1",
                        TRACE,
                        RUN,
                        "Main",
                        Map.of("workflows", List.of()),
                        List.of(Map.of("name", "Refund", "level", "watch"))));
        assertThat(registry.findByTrace(TRACE).orElseThrow().graph()).containsKey("workflows");
        assertThat((List<?>) registry.findByTrace(TRACE).orElseThrow().view().get("ladders"))
                .hasSize(1);
        receiver.heartbeat(
                "p",
                new Heartbeat(
                        "p",
                        "i_1",
                        "x",
                        10,
                        0,
                        0,
                        Map.of(TRACE, List.of(Map.of("name", "Refund", "level", "suggest")))));
        @SuppressWarnings("unchecked")
        Map<String, Object> ladder =
                ((List<Map<String, Object>>)
                                registry.findByTrace(TRACE).orElseThrow().view().get("ladders"))
                        .get(0);
        assertThat(ladder).containsEntry("level", "suggest");
    }

    @Test
    void theProcessRowShowsBacklogLossRateAndLiveRuns() {
        receiver.hello("p", hello("p", 1));
        receiver.logs("p", logs("p", 1, Semconv.EV_RUN_START));
        receiver.heartbeat("p", new Heartbeat("p", "i_1", "x", 100, 40, 3, Map.of()));
        clock.now = clock.now.plusSeconds(5);
        receiver.heartbeat("p", new Heartbeat("p", "i_1", "x", 150, 20, 3, Map.of()));
        assertThat(process("p"))
                .containsEntry("state", "catching up")
                .containsEntry("backlog", 20L)
                .containsEntry("lost", 3L)
                .containsEntry("liveRuns", 1)
                .containsEntry("eventsPerSecond", 10.0);
        receiver.heartbeat("p", new Heartbeat("p", "i_1", "x", 150, 0, 3, Map.of()));
        assertThat(process("p")).containsEntry("state", "online");
    }

    @Test
    void aCleanByeIsNotMistakenForADeadProcess() {
        receiver.hello("p", hello("p", 1));
        receiver.bye("p");
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        assertThat(process("p")).containsEntry("state", "finished");
        assertThat(receiver.online("p")).isFalse();
    }

    @Test
    void garbageOtlpIsRejectedNotThrownRaw() {
        assertThatThrownBy(() -> receiver.logs("p", "{not json"))
                .isInstanceOf(Receiver.Rejected.class);
    }

    @Test
    void aSnapshotsInspectRulesReachTheRunsDetectors() {
        receiver.hello("p", hello("p", 1));
        receiver.logs("p", logs("p", 1, Semconv.EV_RUN_START));
        receiver.snapshot(
                "p",
                new Snapshot(
                        "p",
                        "i_1",
                        TRACE,
                        RUN,
                        "Main",
                        Map.of(),
                        List.of(),
                        Map.of(
                                "Editor",
                                new io.github.llm4j.loom.inspect.state.InspectRules(
                                        2, null, null, null, null, null, null, null))));
        for (long seq = 2; seq <= 3; seq++) {
            LogRec r =
                    new LogRec(
                            1_000_000_000L * (1 + seq),
                            LogRec.INFO,
                            "execute_tool Wiki",
                            Map.of(
                                    Semconv.EVENT_NAME,
                                    Semconv.EV_SPAN_START,
                                    Semconv.LOOM_SEQ,
                                    seq,
                                    Semconv.LOOM_SPAN_KIND,
                                    Semconv.KIND_TOOL,
                                    Semconv.GEN_AI_AGENT_NAME,
                                    "Editor",
                                    Semconv.GEN_AI_TOOL_NAME,
                                    "Wiki",
                                    Semconv.LOOM_STEP,
                                    "Main/s1",
                                    Semconv.LOOM_ACTION_HASH,
                                    "h"),
                            TRACE,
                            Ids.spanId(RUN, "t" + seq));
            receiver.logs("p", OtlpJson.encodeLogs(Resource.of("p", "i_1"), "0.1", List.of(r)));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> incidents =
                (List<Map<String, Object>>)
                        registry.findByTrace(TRACE).orElseThrow().view().get("incidents");
        assertThat(incidents)
                .hasSize(1); // two identical calls is a loop for this agent, though the default is
        // four
    }
}
