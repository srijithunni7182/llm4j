package io.github.llm4j.loom.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The attempts of a run. A rewind never deletes a journal entry: it starts a new <b>generation</b> of the steps from some statement
 * onward, and a statement's step id carries its generation ({@code Main/s5~2}), so the new attempt finds no entries and runs while the
 * old attempt stays as history. The boundaries (where each new generation starts, and why) are kept in the journal itself, as one entry
 * that is read first on every start, so a resumed run goes straight to the latest generation of each block and never visits a discarded one.
 *
 * <p>A boundary names a block (the id of the statement list: {@code Main/s}, {@code Main/s3/a}) and the index of the first statement of
 * the new generation. The generation of statement {@code i} of a block is the highest generation among that block's boundaries whose
 * first statement is at or before {@code i}. Ids nested under a statement inherit its suffix, because they are built from it.
 */
public final class Generations {

    /** The journal key of the boundary list. */
    public static final String KEY = "#boundaries";
    private static final Pattern SUFFIX = Pattern.compile("(\\d+)~(\\d+)");

    /** One new generation. {@code statement} is the rewind statement's own id without any generation suffix. */
    public record Boundary(String block, int from, int generation, String name, String by, String statement,
                           String reason, Map<String, String> carried, String effects, String time) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("block", block);
            m.put("from", from);
            m.put("generation", generation);
            m.put("name", name);
            m.put("by", by);
            m.put("statement", statement);
            m.put("reason", reason);
            m.put("with", carried);
            m.put("effects", effects);
            m.put("time", time);
            return m;
        }

        @SuppressWarnings("unchecked")
        static Boundary of(Map<String, Object> m) {
            Map<String, String> carried = new LinkedHashMap<>();
            Object with = m.get("with");
            if (with instanceof Map<?, ?> w) w.forEach((k, v) -> carried.put(String.valueOf(k), String.valueOf(v)));
            return new Boundary(String.valueOf(m.get("block")), ((Number) m.get("from")).intValue(), ((Number) m.get("generation")).intValue(),
                    str(m.get("name")), str(m.get("by")), str(m.get("statement")), str(m.get("reason")), carried, str(m.get("effects")), str(m.get("time")));
        }

        private static String str(Object o) {
            return o == null ? null : String.valueOf(o);
        }

        /** The id of the first statement of the new generation, as a person reads it. */
        public String fromStep() {
            return block + from + (generation > 1 ? "~" + generation : "");
        }
    }

    private final RunJournal journal;
    private final List<Boundary> boundaries = new ArrayList<>();

    public Generations(RunJournal journal) {
        this.journal = journal;
        load();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        boundaries.clear();
        journal.get(KEY).ifPresent(entry -> {
            if (entry.value() instanceof List<?> list) {
                for (Object o : list) boundaries.add(Boundary.of((Map<String, Object>) o));
            }
        });
    }

    /** True when the run has gone back at least once. */
    public synchronized boolean any() {
        return !boundaries.isEmpty();
    }

    public synchronized List<Boundary> all() {
        return List.copyOf(boundaries);
    }

    /** The generation of statement {@code index} in {@code block}: 1 until a rewind has started a later one. */
    public synchronized int current(String block, int index) {
        int g = 1;
        for (Boundary b : boundaries) if (b.block().equals(block) && b.from() <= index && b.generation() > g) g = b.generation();
        return g;
    }

    /** The generation a new rewind of this block begins. */
    public synchronized int next(String block) {
        int g = 1;
        for (Boundary b : boundaries) if (b.block().equals(block)) g = Math.max(g, b.generation());
        return g + 1;
    }

    /** What the latest boundary starting exactly at {@code index} carried in, or null if no boundary starts there. */
    public synchronized Boundary startingAt(String block, int index) {
        Boundary best = null;
        for (Boundary b : boundaries) {
            if (b.block().equals(block) && b.from() == index && (best == null || b.generation() > best.generation())) best = b;
        }
        return best;
    }

    /** How many times the rewind statement (id without suffix) has rewound the run. */
    public synchronized int rewindsBy(String statement) {
        int n = 0;
        for (Boundary b : boundaries) if (statement.equals(b.statement())) n++;
        return n;
    }

    /** How many times the run has gone back in all. */
    public synchronized int total() {
        return boundaries.size();
    }

    /** Records a new generation. This single journal write is the point a rewind happens: before it nothing changed, after it the run is in the new generation. */
    public synchronized Boundary start(String block, int from, String name, String by, String statement, String reason,
                                       Map<String, String> carried, String effects) {
        Boundary b = new Boundary(block, from, next(block), name, by, statement, reason, new LinkedHashMap<>(carried), effects, Instant.now().toString());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Boundary old : boundaries) list.add(old.toMap());
        list.add(b.toMap());
        journal.put(KEY, new RunJournal.Entry("boundaries", list));
        boundaries.add(b);
        return b;
    }

    /** The step id as an effect or a person's answer identifies it: the same place in the script, whatever attempt reached it. */
    public static String strip(String step) {
        return SUFFIX.matcher(step).replaceAll("$1");
    }

    /**
     * As {@link #strip}, except for generations that were started with {@code side effects: repeat}: their effects are meant to run
     * again, so they keep the suffix and find no earlier record.
     */
    public synchronized String identity(String step) {
        if (boundaries.stream().noneMatch(b -> "repeat".equals(b.effects()))) return strip(step);
        Matcher m = SUFFIX.matcher(step);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String block = step.substring(0, m.start(1));
            int generation = Integer.parseInt(m.group(2));
            boolean repeat = boundaries.stream().anyMatch(b -> b.block().equals(block) && b.generation() == generation && "repeat".equals(b.effects()));
            m.appendReplacement(out, repeat ? m.group(0) : m.group(1));
        }
        m.appendTail(out);
        return out.toString();
    }
}
