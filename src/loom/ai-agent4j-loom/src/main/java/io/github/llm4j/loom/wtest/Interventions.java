package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.StopOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What a person does to a running agent in a case: {@code steer:} says something to it after one of
 * its model calls, {@code stop:} asks it to wrap up or cancels it. They go through the executor's
 * own steering and stopping, so a steerable agent, an {@code on_stopped} block and the run's
 * defaults behave as they do for a person.
 */
final class Interventions {

    private record Steer(String agent, int afterCall, int afterChunk, String text) {}

    private record Stop(
            String agent, int afterCall, int afterChunk, String mode, StopOutcome outcome) {}

    private final List<Steer> steers = new ArrayList<>();
    private Stop stop;
    private final AtomicReference<HarnessExecutor> executor = new AtomicReference<>();

    Interventions(JsonNode steer, JsonNode stopSpec) {
        if (steer != null && steer.isArray())
            for (JsonNode s : steer) {
                boolean chunk = s.has("after_chunk");
                var at = s.path(chunk ? "after_chunk" : "after_call");
                var names = at.fieldNames();
                if (names.hasNext()) {
                    String agent = names.next();
                    int n = at.get(agent).asInt();
                    steers.add(
                            new Steer(
                                    agent, chunk ? 0 : n, chunk ? n : 0, s.path("text").asText()));
                }
            }
        if (stopSpec != null && (stopSpec.has("after_call") || stopSpec.has("after_chunk"))) {
            boolean chunk = stopSpec.has("after_chunk");
            var call = stopSpec.get(chunk ? "after_chunk" : "after_call");
            String agent = call.fieldNames().next();
            String mode = stopSpec.path("mode").asText("cancel");
            StopOutcome outcome = null;
            if (stopSpec.has("outcome"))
                outcome =
                        StopOutcome.valueOf(
                                stopSpec.get("outcome").asText().toUpperCase(Locale.ROOT));
            int n = call.get(agent).asInt();
            stop = new Stop(agent, chunk ? 0 : n, chunk ? n : 0, mode, outcome);
        }
    }

    void attach(HarnessExecutor e) {
        executor.set(e);
    }

    /** Called when the agent has answered its {@code n}th model call. */
    void afterCall(String agent, int n) {
        happen(agent, n, 0);
    }

    /** Called when the {@code k}th piece of a streamed reply has been read. */
    void afterChunk(String agent, int k) {
        happen(agent, 0, k);
    }

    private void happen(String agent, int call, int chunk) {
        HarnessExecutor e = executor.get();
        if (e == null) return;
        for (Steer s : steers)
            if (s.agent().equals(agent)
                    && (call > 0 ? s.afterCall() == call : s.afterChunk() == chunk && chunk > 0))
                e.steer(agent, s.text(), "test");
        if (stop != null
                && stop.agent().equals(agent)
                && (call > 0
                        ? stop.afterCall() == call
                        : stop.afterChunk() == chunk && chunk > 0)) {
            if (stop.mode().equals("wrap_up")) e.wrapUp(agent, "stopped by the test", "test");
            else e.cancel(agent, "stopped by the test", "test", stop.outcome());
        }
    }
}
