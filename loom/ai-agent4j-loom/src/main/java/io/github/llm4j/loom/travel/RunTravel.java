package io.github.llm4j.loom.travel;

import io.github.llm4j.loom.runtime.EffectScan;
import io.github.llm4j.loom.runtime.Generations;
import io.github.llm4j.loom.runtime.RunJournal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What an operator can do to a run from outside: look at its history, send it back to an earlier point, clear it, or branch a copy off.
 * Everything works on the run's journal and leaves the history in it; nothing here runs a model.
 */
public final class RunTravel {

    private RunTravel() { }

    /** A rewind that the effects policy holds back, with what it would cross. */
    public static final class Held extends RuntimeException {
        private final List<String> effects;

        Held(List<String> effects) {
            super("Going back would cross side effects that already happened: " + String.join("; ", effects)
                    + ". Say what to do about them: --effects keep (identical ones are not repeated) or --effects repeat (all run again).");
            this.effects = effects;
        }

        public List<String> effects() {
            return effects;
        }
    }

    // ---- where to go ----------------------------------------------------------------------------------------

    /** The first statement of the attempt a rewind starts: a block and the index in it. */
    public record Target(String block, int from, String name) { }

    /**
     * {@code to} is a checkpoint name ({@code start} is the point before the first statement) or the id of a statement the run has
     * executed, which is the first one that will run again. Throws with the nearest valid ids when it is neither.
     */
    public static Target resolve(RunJournal journal, String workflow, String to) {
        Generations generations = new Generations(journal);
        if (to.equals("start")) return new Target(workflow + "/s", 0, "start");
        for (Map.Entry<String, RunJournal.Entry> e : journal.all().entrySet()) {
            if (!e.getKey().endsWith("#checkpoint") || !(e.getValue().value() instanceof Map<?, ?> m) || !to.equals(m.get("name"))) continue;
            String step = Generations.strip(e.getKey().substring(0, e.getKey().length() - "#checkpoint".length()));
            Generations.Located at = generations.locate(step);
            if (at != null) return new Target(at.block(), at.index() + 1, to);
        }
        String free = Generations.strip(to);
        boolean executed = journal.all().keySet().stream().map(Generations::strip)
                .anyMatch(k -> k.equals(free) || k.startsWith(free + "/") || k.startsWith(free + "#"));
        Generations.Located at = executed ? generations.locate(free) : null;
        if (at == null || free.matches(".*/[ep]\\d+(/.*)?")) {
            throw new IllegalArgumentException("\"" + to + "\" is not a checkpoint of this run or a statement it has executed outside a parallel branch. "
                    + "Try one of: " + validTargets(journal));
        }
        return new Target(at.block(), at.index(), null);
    }

    private static String validTargets(RunJournal journal) {
        TreeSet<String> names = new TreeSet<>(Comparator.naturalOrder());
        names.add("start");
        List<String> steps = new ArrayList<>();
        for (Map.Entry<String, RunJournal.Entry> e : journal.all().entrySet()) {
            if (e.getKey().endsWith("#checkpoint") && e.getValue().value() instanceof Map<?, ?> m && m.get("name") != null) names.add(String.valueOf(m.get("name")));
            if (!e.getKey().contains("#") && !e.getKey().contains("~") && e.getKey().matches("[^/]+/s\\d+")) steps.add(e.getKey());
        }
        steps.sort(Comparator.naturalOrder());
        names.addAll(steps.subList(0, Math.min(steps.size(), 8)));
        return String.join(", ", names);
    }

    // ---- rewind ---------------------------------------------------------------------------------------------

    /**
     * Sends the run back, exactly as a {@code rewind} statement would: a new attempt of the statements from the target on.
     *
     * @param effects {@code ask first} (refuse if effects were performed), {@code keep} or {@code repeat}
     */
    public static Generations.Boundary rewind(RunJournal journal, String workflow, String to, Map<String, String> carried, String effects,
                                              boolean askAgain, String reason, String by) {
        Target target = resolve(journal, workflow, to);
        List<String> blockers = EffectScan.effectsIn(journal, target.block(), target.from());
        if (!blockers.isEmpty() && "ask first".equals(effects)) throw new Held(blockers);
        Generations generations = new Generations(journal);
        return generations.start(target.block(), target.from(), target.name() == null ? to : target.name(), by, null, reason, carried, effects, askAgain);
    }

    /**
     * Starts the run again from the top, as a new attempt, and clears a recorded pause. With {@code failedOnly}, instead marks each step
     * that failed so that it is tried again (its {@code on_failure} runs afresh) and leaves everything else as it was.
     *
     * @return what was changed, for the audit log
     */
    public static List<String> reset(RunJournal journal, String workflow, boolean failedOnly, String effects, String reason, String by) {
        List<String> changed = new ArrayList<>();
        if (failedOnly) {
            for (Map.Entry<String, RunJournal.Entry> e : new ArrayList<>(journal.all().entrySet())) {
                if (e.getKey().contains("#") || !"failed".equals(e.getValue().kind())) continue;
                journal.put(e.getKey(), new RunJournal.Entry("retry", e.getValue().value()));
                changed.add(e.getKey() + " (failed: " + e.getValue().value() + ")");
            }
            return changed;
        }
        Generations.Boundary b = rewind(journal, workflow, "start", Map.of(), effects, false, reason, by);
        changed.add("rewound to start (generation " + b.generation() + ")");
        journal.get("#suspension").ifPresent(s -> {
            Map<String, Object> cleared = new LinkedHashMap<>();
            if (s.value() instanceof Map<?, ?> m) m.forEach((k, v) -> cleared.put(String.valueOf(k), v));
            cleared.put("state", "cleared");
            journal.put("#suspension", new RunJournal.Entry(s.kind(), cleared));
            changed.add("cleared the recorded pause");
        });
        return changed;
    }

    // ---- fork -----------------------------------------------------------------------------------------------

    /** Copies every entry of one journal into another (a materialized fork). The first is only read. */
    public static int copy(RunJournal from, RunJournal into) {
        Map<String, RunJournal.Entry> all = from.all();
        all.forEach(into::put);
        return all.size();
    }

    // ---- timeline -------------------------------------------------------------------------------------------

    /** One statement's step in the run's history. */
    public record Row(String id, int generation, String kind, String agent, String state, long tokens, BigDecimal cost) { }

    public record Timeline(List<Row> steps, List<String> checkpoints, List<Generations.Boundary> boundaries, long keptTokens, BigDecimal keptCost,
                           long discardedTokens, BigDecimal discardedCost) {

        public String text() {
            StringBuilder b = new StringBuilder();
            b.append(String.format("%-28s %-4s %-10s %-12s %-10s %8s %10s%n", "STEP", "GEN", "KIND", "AGENT", "STATE", "TOKENS", "COST"));
            for (Row r : steps) {
                b.append(String.format("%-28s %-4d %-10s %-12s %-10s %8d %10s%n", r.id(), r.generation(), r.kind(), r.agent() == null ? "" : r.agent(), r.state(), r.tokens(), r.cost().toPlainString()));
            }
            if (!checkpoints.isEmpty()) b.append("\nCheckpoints reached: ").append(String.join(", ", checkpoints)).append('\n');
            if (!boundaries.isEmpty()) {
                b.append("\nRewinds:\n");
                for (Generations.Boundary g : boundaries) {
                    b.append("  generation ").append(g.generation()).append(" from ").append(g.fromStep()).append(" (back to ").append(g.name()).append(") by ").append(g.by())
                            .append(": ").append(g.reason()).append("; side effects: ").append(g.effects());
                    if (!g.carried().isEmpty()) b.append("; carrying ").append(g.carried());
                    b.append('\n');
                }
            }
            b.append(String.format("%nSpend: kept %d tokens / %s, discarded (replaced attempts) %d tokens / %s%n", keptTokens, keptCost.toPlainString(), discardedTokens, discardedCost.toPlainString()));
            return b.toString();
        }
    }

    public static Timeline timeline(RunJournal journal) {
        Generations generations = new Generations(journal);
        Map<String, RunJournal.Entry> all = journal.all();
        Map<String, long[]> tokens = new LinkedHashMap<>();
        Map<String, BigDecimal> costs = new LinkedHashMap<>();
        Map<String, String> agents = new LinkedHashMap<>();
        for (Map.Entry<String, RunJournal.Entry> e : all.entrySet()) {
            int at = e.getKey().indexOf("#usage:");
            if (at < 0 || !(e.getValue().value() instanceof Map<?, ?> u)) continue;
            String step = e.getKey().substring(0, at);
            tokens.merge(step, new long[] {number(u.get("prompt")) + number(u.get("completion"))}, (a, b) -> new long[] {a[0] + b[0]});
            costs.merge(step, money(u.get("cost")), BigDecimal::add);
            agents.put(step, String.valueOf(u.get("agent")));
        }
        List<Row> rows = new ArrayList<>();
        List<String> checkpoints = new ArrayList<>();
        long keptTokens = 0, discardedTokens = 0;
        BigDecimal keptCost = BigDecimal.ZERO, discardedCost = BigDecimal.ZERO;
        List<String> keys = new ArrayList<>(all.keySet());
        keys.sort(Comparator.comparing(RunTravel::sortKey));
        for (String key : keys) {
            RunJournal.Entry entry = all.get(key);
            if (key.endsWith("#checkpoint") && entry.value() instanceof Map<?, ?> m) checkpoints.add(m.get("name") + " (" + key.substring(0, key.length() - 11) + ")");
            if (key.contains("#")) continue;
            String kind = entry.kind();
            if (!List.of("delegate", "handoff", "broadcast", "human", "failed", "retry").contains(kind)) continue;
            boolean current = generations.isCurrent(key);
            String state = !current ? "discarded" : "failed".equals(kind) ? "failed" : "retry".equals(kind) ? "retry" : "done";
            long t = tokens.getOrDefault(key, new long[] {0})[0];
            BigDecimal c = costs.getOrDefault(key, BigDecimal.ZERO);
            rows.add(new Row(key, generationOf(key), kind, agents.get(key), state, t, c));
        }
        for (Map.Entry<String, long[]> e : tokens.entrySet()) {
            BigDecimal c = costs.getOrDefault(e.getKey(), BigDecimal.ZERO);
            if (generations.isCurrent(e.getKey())) { keptTokens += e.getValue()[0]; keptCost = keptCost.add(c); }
            else { discardedTokens += e.getValue()[0]; discardedCost = discardedCost.add(c); }
        }
        return new Timeline(rows, checkpoints, generations.all(), keptTokens, keptCost, discardedTokens, discardedCost);
    }

    private static int generationOf(String step) {
        int generation = 1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("~(\\d+)").matcher(step);
        while (m.find()) generation = Math.max(generation, Integer.parseInt(m.group(1)));
        return generation;
    }

    /** Orders step ids the way a person reads them: numbers as numbers, an attempt after the one it replaced. */
    private static String sortKey(String key) {
        StringBuilder b = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(key);
        int last = 0;
        while (m.find()) {
            b.append(key, last, m.start()).append(String.format("%08d", Long.parseLong(m.group())));
            last = m.end();
        }
        return b.append(key.substring(last)).toString();
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static BigDecimal money(Object o) {
        try {
            return o == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** When an operator acted, for the audit log. */
    public static String now() {
        return Instant.now().toString();
    }
}
