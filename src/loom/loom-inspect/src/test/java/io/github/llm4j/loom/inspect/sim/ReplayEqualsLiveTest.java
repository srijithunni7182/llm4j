package io.github.llm4j.loom.inspect.sim;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.inspect.state.RunState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/** R31: a past view comes from the same code as the live view: over real runs, the view at the last point is the live view, and earlier points are consistent with the marks. */
class ReplayEqualsLiveTest {

    @TempDir
    Path dir;

    private static Map<String, Object> plain(Map<String, Object> v) {
        Map<String, Object> m = new LinkedHashMap<>(v);
        for (String k : List.of("history", "live", "asOf", "seq")) m.remove(k);
        return m;
    }

    @Test
    void theViewAtTheEndOfAFinishedRunIsItsLiveView() {
        for (String name : List.of("context-fills", "flaky-tool", "task-broken", "task-diverted", "trifecta", "injected-page", "access-refused", "no-window", "truncated-answers")) endOf(name);
    }

    private void endOf(String name) {
        RunRegistry registry = new RunRegistry();
        Scenarios.runInto(registry, "sim", Scenarios.get(name), "run-" + name, dir);
        RunState run = registry.find("sim", Ids.traceId("run-" + name)).orElseThrow();
        Map<String, Object> at = run.viewAt(run.lastSeq()).orElseThrow();
        Instant asOf = Instant.parse(String.valueOf(at.get("asOf")));
        assertThat(plain(at)).as(name).isEqualTo(plain(run.view(asOf)));
    }

    @Test
    void earlierPointsGrowTowardsTheEndAndAgreeWithTheMarks() {
        for (String name : List.of("context-fills", "flaky-tool", "task-broken", "trifecta")) along(name);
    }

    @SuppressWarnings("unchecked")
    private void along(String name) {
        RunRegistry registry = new RunRegistry();
        Scenarios.runInto(registry, "sim", Scenarios.get(name), "run-" + name, dir);
        RunState run = registry.find("sim", Ids.traceId("run-" + name)).orElseThrow();
        long last = run.lastSeq();
        long calls = -1;
        for (long k = 0; k <= last; k += Math.max(1, last / 25)) {
            Map<String, Object> v = run.viewAt(k).orElseThrow();
            long c = ((Number) ((Map<String, Object>) v.get("totals")).get("calls")).longValue();
            assertThat(c).as("calls never go down as the point moves on").isGreaterThanOrEqualTo(calls);
            calls = c;
        }
        for (Map<String, Object> m : run.timeline()) {
            if (!"incident-open".equals(m.get("kind"))) continue;
            long seq = (Long) m.get("seq");
            List<Map<String, Object>> at = (List<Map<String, Object>>) run.viewAt(seq).orElseThrow().get("incidents");
            assertThat(at).as(name + ": the incident is in the view at its mark").anyMatch(i -> String.valueOf(m.get("label")).equals(String.valueOf(i.get("title"))));
            if (seq > 0) {
                List<Map<String, Object>> before = (List<Map<String, Object>>) run.viewAt(seq - 1).orElseThrow().get("incidents");
                assertThat(before).as(name + ": and not in the view just before").noneMatch(i -> String.valueOf(m.get("label")).equals(String.valueOf(i.get("title"))) && "open".equals(i.get("state")));
            }
        }
    }
}
