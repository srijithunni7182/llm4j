package io.github.llm4j.loom.host;

import static io.github.llm4j.loom.host.HostEnv.SCHEDULED;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.trigger.Trigger;
import io.github.llm4j.loom.trigger.TriggerRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Schedules and wake-ups fired by the host. V8.19–V8.23. */
class LoomHostTriggersTest {

    @TempDir
    Path dir;

    private static long schedules(HostEnv env) {
        return env.persistence().triggers().all().stream().filter(t -> t.id().startsWith("schedule:")).count();
    }

    @Tag("SB-V8.19")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void aScheduledWorkflowStartsOnceAtItsTime(String kind) {
        HostEnv env = new HostEnv(kind, dir);
        env.clock.set("2026-10-15T06:59:00Z");
        env.open(SCHEDULED, b -> b.scriptRef("script-a"));
        List<TriggerRunner.Fired> fired = Collections.synchronizedList(new ArrayList<>());
        env.host.startTriggers(Duration.ofMillis(20), "host-1", Duration.ofMinutes(5), fired::add);
        try {
            env.clock.advance(Duration.ofMinutes(2)); // 07:01
            HostEnv.await(() -> env.host.runs().size() == 1 && env.host.runs().get(0).status() == RunRecord.Status.COMPLETED, "the scheduled run");
            assertThat(env.host.runs().get(0).runId()).startsWith("Daily@2026-10-15T07:00");
            assertThat(env.host.runs().get(0).origin()).isEqualTo("trigger");
            HostEnv.await(() -> !fired.isEmpty(), "the fired event");
        } finally {
            env.host.stopTriggers();
        }
        assertThat(env.models.calls("Writer")).isEqualTo(1);
        assertThat(fired).hasSize(1);
        assertThat(fired.get(0).trigger().target()).isInstanceOf(Trigger.StartWorkflow.class);
    }

    @Tag("SB-V8.20")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void twoHostsOnOneStoreFireATriggerOnce(String kind) throws Exception {
        HostEnv env = new HostEnv(kind, dir);
        env.clock.set("2026-10-15T06:59:00Z");
        env.open(SCHEDULED, b -> b.scriptRef("script-a"));
        LoomHost second = env.builder(SCHEDULED).scriptRef("script-a").build();
        env.host.startTriggers(Duration.ofMillis(10), "host-1", Duration.ofMinutes(5), null);
        second.startTriggers(Duration.ofMillis(10), "host-2", Duration.ofMinutes(5), null);
        try {
            env.clock.advance(Duration.ofMinutes(2));
            HostEnv.await(() -> env.host.runs().size() == 1 && env.host.runs().get(0).status() == RunRecord.Status.COMPLETED, "the scheduled run");
            Thread.sleep(300); // a duplicate, if there were one, would have started by now
        } finally {
            env.host.stopTriggers();
            second.stopTriggers();
        }
        assertThat(env.host.runs()).hasSize(1);
        assertThat(env.models.calls("Writer")).isEqualTo(1);
    }

    @Tag("SB-V8.21")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void restartingTheHostDoesNotDuplicateTheSchedule(String kind) {
        HostEnv env = new HostEnv(kind, dir);
        env.clock.set("2026-10-15T06:59:00Z");
        for (int i = 0; i < 3; i++) {
            env.open(SCHEDULED, b -> b.scriptRef("script-a"));
            env.host.registerSchedules();
        }
        assertThat(schedules(env)).isEqualTo(1);
        // a run's executor shares the store, so a run does not add or move the schedule either
        env.host.run(RunRequest.of("Digest", java.util.Map.of()));
        assertThat(schedules(env)).isEqualTo(1);
    }

    @Tag("SB-V8.23")
    @ParameterizedTest @ValueSource(strings = {"file", "jdbc"})
    void aTaskGivenToAnAgentRunsWithoutStartingARun(String kind) {
        String script = HostEnv.AGENTS + "schedule Sweep { every: 1h agent: Writer task: \"tidy up\" }\nworkflow Main() { note \"x\" }\n";
        HostEnv env = new HostEnv(kind, dir);
        env.open(script, b -> b.scriptRef("script-a"));
        env.host.startTriggers(Duration.ofMillis(20), "host-1", Duration.ofMinutes(5), null);
        try {
            env.clock.advance(Duration.ofHours(1).plusMinutes(1));
            HostEnv.await(() -> env.models.calls("Writer") == 1, "the agent to be given its task");
        } finally {
            env.host.stopTriggers();
        }
        assertThat(env.host.runs()).isEmpty();
        assertThat(schedules(env)).isEqualTo(1);
    }
}
