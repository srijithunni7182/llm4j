package io.github.llm4j.loom.inspect.map;

import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.inspect.state.InspectRules;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the variables a script's {@code no_progress} rules name, once at the boundary between two rounds of a loop. The first event of a
 * new round is when the round before it has finished and its result has been stored, so what the variable holds then is what that
 * round ended with. The values themselves never leave the process: only a short hash of each, as a sample record.
 *
 * <p>Runs on the event sink's thread. The variables are read from a copy the run hands out, never changed.
 */
public final class ProgressSampler {

    private static final Pattern ROUND = Pattern.compile("^(.+?)/r(\\d+)\\.");

    private final Map<String, InspectRules> rules;
    private final Supplier<Map<String, Object>> variables;
    private final Map<String, Integer> lastRound = new HashMap<>();

    public ProgressSampler(Map<String, InspectRules> rules, Supplier<Map<String, Object>> variables) {
        this.rules = rules;
        this.variables = variables;
    }

    /** True when some agent has a no_progress rule, so reading the variables is worth doing at all. */
    public boolean active() {
        return rules.values().stream().anyMatch(r -> r.noProgressRounds() != null && r.noProgressVariable() != null);
    }

    /** The event type of the synthetic events this produces; the mapper turns each into a sample record. */
    public static final String SAMPLE = "inspect_sample";

    /**
     * Called on the run's own thread, for each event, before it is queued: the value must be read now, not when the queue is read, or a
     * slow reader would see a later round's value. Returns the samples to queue ahead of the event (usually none).
     */
    public synchronized java.util.List<TraceEvent> samples(TraceEvent e) {
        if (!active() || e.step() == null) return java.util.List.of();
        Matcher m = ROUND.matcher(e.step());
        if (!m.find()) return java.util.List.of();
        String loop = m.group(1);
        int round = Integer.parseInt(m.group(2));
        Integer before = lastRound.put(loop, round);
        if (before == null || before == round) return java.util.List.of();
        Map<String, Object> vars;
        try {
            vars = variables.get();
        } catch (RuntimeException ex) {
            return java.util.List.of();
        }
        java.util.List<TraceEvent> out = new java.util.ArrayList<>();
        for (Map.Entry<String, InspectRules> r : rules.entrySet()) {
            InspectRules rule = r.getValue();
            if (rule.noProgressRounds() == null || rule.noProgressVariable() == null) continue;
            Object value = path(vars, rule.noProgressVariable());
            if (value == null) continue;
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("agent", r.getKey());
            data.put("variable", rule.noProgressVariable());
            data.put("loop", loop);
            data.put("hash", Signals.hash(String.valueOf(value)));
            out.add(new TraceEvent(SAMPLE, null, e.step(), "", data, e.at()));
        }
        return out;
    }

    /** {@code review.score}: the variable, then a field of it when it holds a map. Null when any step of the way is missing. */
    static Object path(Map<String, Object> vars, String dotted) {
        Object current = vars;
        for (String part : dotted.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
            if (current == null) return null;
        }
        return current;
    }
}
