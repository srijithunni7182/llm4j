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

    private record Steer(String agent, int afterCall, String text) {}

    private record Stop(String agent, int afterCall, String mode, StopOutcome outcome) {}

    private final List<Steer> steers = new ArrayList<>();
    private Stop stop;
    private final AtomicReference<HarnessExecutor> executor = new AtomicReference<>();

    Interventions(JsonNode steer, JsonNode stopSpec) {
        if (steer != null && steer.isArray())
            for (JsonNode s : steer) {
                var call = s.path("after_call");
                var names = call.fieldNames();
                if (names.hasNext()) {
                    String agent = names.next();
                    steers.add(new Steer(agent, call.get(agent).asInt(), s.path("text").asText()));
                }
            }
        if (stopSpec != null && stopSpec.has("after_call")) {
            var call = stopSpec.get("after_call");
            String agent = call.fieldNames().next();
            String mode = stopSpec.path("mode").asText("cancel");
            StopOutcome outcome = null;
            if (stopSpec.has("outcome"))
                outcome =
                        StopOutcome.valueOf(
                                stopSpec.get("outcome").asText().toUpperCase(Locale.ROOT));
            stop = new Stop(agent, call.get(agent).asInt(), mode, outcome);
        }
    }

    void attach(HarnessExecutor e) {
        executor.set(e);
    }

    /** Called when the agent has answered its {@code n}th model call. */
    void afterCall(String agent, int n) {
        HarnessExecutor e = executor.get();
        if (e == null) return;
        for (Steer s : steers)
            if (s.agent().equals(agent) && s.afterCall() == n) e.steer(agent, s.text(), "test");
        if (stop != null && stop.agent().equals(agent) && stop.afterCall() == n) {
            if (stop.mode().equals("wrap_up")) e.wrapUp(agent, "stopped by the test", "test");
            else e.cancel(agent, "stopped by the test", "test", stop.outcome());
        }
    }
}
