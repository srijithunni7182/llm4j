package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.ProgressDecl;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.runtime.Generations;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A workflow's declared progress as the journal keeps it (phase 34b, D6): one entry for each
 * {@code report}, in the <b>child's own</b> journal under {@code <step>#progress}, so it survives a
 * restart, is found again by whoever reads the child, and a rewind (a new generation of the steps)
 * leaves the value as it was at the checkpoint. It is data about the child written by the child, so
 * it is typed, bounded and read as {@code unchecked}: nothing here is control.
 */
final class ProgressLog {

    /** Journal key suffix of a report: {@code <step id>#progress}. */
    static final String KEY = "#progress";

    /** Journal kind of an accepted report. */
    static final String KIND = "progress";

    /** Journal kind of a report that was dropped for being over a bound: said, not silent. */
    static final String DROPPED = "progress-dropped";

    /** The most characters one report's values and field names may hold in all. */
    static final int MAX_CHARS = 2000;

    /** The most reports a run keeps. */
    static final int MAX_REPORTS = 1000;

    /** The most dropped reports that are written down; later ones are only counted in the audit. */
    static final int MAX_DROPPED_RECORDS = 100;

    private final AtomicInteger accepted = new AtomicInteger(-1);
    private final AtomicInteger dropped = new AtomicInteger(-1);

    /** What one {@code report} did. */
    record Result(boolean kept, String reason) {}

    /**
     * Checks a report against the declaration and the bounds and writes it. Called once for each
     * execution of the statement: a replayed one finds its entry and does nothing.
     *
     * @throws IllegalStateException for a field that is not declared or a value of the wrong type;
     *     it fails the step
     */
    Result write(RunJournal journal, String stepId, ProgressDecl decl, Map<String, Object> fields, long nowMillis) {
        String key = stepId + KEY;
        if (journal.get(key).isPresent()) return new Result(true, "replayed");
        validate(decl, fields);
        int size = 0;
        for (Map.Entry<String, Object> e : fields.entrySet()) size += e.getKey().length() + String.valueOf(e.getValue()).length();
        initialise(journal);
        String over = null;
        if (size > MAX_CHARS) over = "a report may hold " + MAX_CHARS + " characters in all and this one held " + size;
        else if (accepted.get() >= MAX_REPORTS) over = "a run keeps " + MAX_REPORTS + " reports and this is past the " + MAX_REPORTS;
        if (over != null) {
            int n = dropped.incrementAndGet();
            if (n < MAX_DROPPED_RECORDS)
                journal.put(key, new RunJournal.Entry(DROPPED, Map.of("reason", over, "after", accepted.get())));
            return new Result(false, over);
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("seq", accepted.incrementAndGet());
        value.put("fields", new LinkedHashMap<>(fields));
        value.put("at", nowMillis);
        journal.put(key, new RunJournal.Entry(KIND, value));
        return new Result(true, "kept");
    }

    /** Counts what an earlier run of this child already kept, once. */
    private void initialise(RunJournal journal) {
        if (accepted.get() >= 0) return;
        int kept = 0;
        int lost = 0;
        for (RunJournal.Entry e : journal.all().values()) {
            if (KIND.equals(e.kind())) kept++;
            else if (DROPPED.equals(e.kind())) lost++;
        }
        accepted.compareAndSet(-1, kept);
        dropped.compareAndSet(-1, lost);
    }

    private static void validate(ProgressDecl decl, Map<String, Object> fields) {
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            SchemaDef type = decl.fields().get(e.getKey());
            if (type == null)
                throw new IllegalStateException(
                        "report: " + e.getKey() + " is not a progress field of this workflow. It declares: "
                                + String.join(", ", decl.fields().keySet()) + ".");
            Object v = e.getValue();
            boolean ok =
                    switch (type.getType()) {
                        case STRING -> v instanceof CharSequence;
                        case NUMBER -> v instanceof Number || (v instanceof CharSequence s && isNumber(s.toString()));
                        case BOOLEAN -> v instanceof Boolean || "true".equals(String.valueOf(v)) || "false".equals(String.valueOf(v));
                        case ENUM -> type.getEnumValues().contains(String.valueOf(v));
                        default -> false;
                    };
            if (!ok)
                throw new IllegalStateException(
                        "report: " + e.getKey() + " is declared "
                                + (type.getType() == SchemaDef.Type.ENUM
                                        ? "enum" + type.getEnumValues().stream().map(x -> "\"" + x + "\"").toList()
                                        : type.getType().name().toLowerCase())
                                + " and was given " + describe(v) + ".");
        }
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s.strip());
            return !s.isBlank();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String describe(Object v) {
        String text = String.valueOf(v);
        return "\"" + (text.length() > 40 ? text.substring(0, 40) + "..." : text) + "\"";
    }

    /**
     * The child's latest report: the highest sequence among the reports of steps that are still the
     * current attempt (a rewound generation's are history), or an empty map when it has reported
     * nothing. Read from the journal every time, so it is the same after a restart.
     */
    static Map<String, Object> latest(RunJournal journal) {
        Generations generations = new Generations(journal);
        long best = -1;
        Map<String, Object> fields = Map.of();
        for (Map.Entry<String, RunJournal.Entry> e : journal.all().entrySet()) {
            if (!KIND.equals(e.getValue().kind()) || !e.getKey().endsWith(KEY)) continue;
            String step = e.getKey().substring(0, e.getKey().length() - KEY.length());
            if (!generations.isCurrent(step)) continue;
            if (!(e.getValue().value() instanceof Map<?, ?> m) || !(m.get("seq") instanceof Number n) || n.longValue() <= best)
                continue;
            if (!(m.get("fields") instanceof Map<?, ?> f)) continue;
            Map<String, Object> copy = new LinkedHashMap<>();
            f.forEach((k, v) -> copy.put(String.valueOf(k), v));
            best = n.longValue();
            fields = copy;
        }
        return fields;
    }
}
