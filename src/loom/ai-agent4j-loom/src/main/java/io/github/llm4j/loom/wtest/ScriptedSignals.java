package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.runtime.ChildHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code signals:} of a {@code weave test} case (phase 34c, D15): signals that arrive from a child
 * at a time on the virtual clock, as if the child had sent them, so a supervisor that waits for one
 * is tested without a child that sends it. The key is the handle's variable, and for a keyed handle
 * the variable and the key joined by an underscore ({@code work_a} for {@code work["a"]}). Each
 * arrival is delivered once, the first time the clock has reached it.
 */
final class ScriptedSignals {

    private static final class Arrival {
        final String handle;
        final long atMillis;
        final String name;
        final String text;
        final boolean sticky;
        boolean delivered;

        Arrival(String handle, long atMillis, String name, String text, boolean sticky) {
            this.handle = handle;
            this.atMillis = atMillis;
            this.name = name;
            this.text = text;
            this.sticky = sticky;
        }
    }

    private final List<Arrival> arrivals = new ArrayList<>();
    private int counter;

    ScriptedSignals(JsonNode signals) {
        if (signals == null || !signals.isObject()) return;
        signals.fields().forEachRemaining(e -> {
            for (JsonNode a : e.getValue()) {
                Duration at = Durations.parse(a.path("at").asText("0s"));
                arrivals.add(new Arrival(e.getKey(), at == null ? 0 : at.toMillis(), a.path("name").asText(), a.has("text") ? a.get("text").asText() : "", a.path("sticky").asBoolean(false)));
            }
        });
        arrivals.sort(java.util.Comparator.comparingLong(a -> a.atMillis));
    }

    /** Delivers every arrival the clock has reached to its child. */
    void deliverDue(Duration elapsed, HarnessExecutor executor) {
        for (Arrival a : arrivals) {
            if (a.delivered || a.atMillis > elapsed.toMillis()) continue;
            ChildHandle handle = handleOf(a.handle, executor.getContext().getAll());
            if (handle == null || handle.status().isSettled()) continue; // not started yet, or nothing left to signal from
            a.delivered = true;
            // stamped with its scripted time: one long step of a scripted child can carry the clock past it
            long at = executor.clockMillis() - elapsed.toMillis() + a.atMillis;
            executor.simulateSignalFromChild(handle, a.name, a.text, a.sticky, "scripted/" + (counter++), at);
        }
    }

    /** Milliseconds until the next arrival that has not come, or a very large number. */
    long untilNext(Duration elapsed) {
        long best = Long.MAX_VALUE;
        for (Arrival a : arrivals) if (!a.delivered) best = Math.min(best, Math.max(1, a.atMillis - elapsed.toMillis()));
        return best;
    }

    private static ChildHandle handleOf(String key, Map<String, Object> variables) {
        if (variables.get(key) instanceof ChildHandle h) return h;
        for (int i = key.indexOf('_'); i > 0; i = key.indexOf('_', i + 1))
            if (variables.get(key.substring(0, i)) instanceof Map<?, ?> m && m.get(key.substring(i + 1)) instanceof ChildHandle h) return h;
        return null;
    }
}
