package io.github.llm4j.loom.loop;

import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.ObserveReplay;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which past runs are worth turning into a test: the ones that failed, were diverted to a handler,
 * had a classifier that was unsure, or where a person stepped in. Chosen by reading the journals
 * (and replaying them without a model); no time or cost is claimed, because the journal does not
 * keep either.
 */
public final class Candidates {

    private Candidates() {}

    /** The classes of run a developer can pick. */
    public static final List<String> CLASSES =
            List.of("failed", "diverted", "unsure", "overridden");

    /** Classes that the journal cannot answer, with why. */
    public static String unavailable(String cls) {
        if (cls.equals("slow") || cls.equals("costly"))
            return cls
                    + " runs cannot be picked: a run's journal keeps what happened, not how long it"
                    + " took or what it cost (phase 13, decision D11 asks whether it should).";
        return null;
    }

    /** A run and why it is a candidate. */
    public record Candidate(RunRecord run, Set<String> classes, String status) {}

    public static List<Candidate> list(List<RunRecord> runs, Instant since) {
        List<Candidate> out = new ArrayList<>();
        for (RunRecord run : runs) {
            if (since != null && run.modified().isBefore(since)) continue;
            Candidate c = classify(run);
            if (!c.classes().isEmpty()) out.add(c);
        }
        return out;
    }

    /** The newest {@code limit} candidates of one class. */
    public static List<Candidate> select(List<Candidate> all, String cls, int limit) {
        String u = unavailable(cls);
        if (u != null) throw new IllegalArgumentException(u);
        if (!CLASSES.contains(cls))
            throw new IllegalArgumentException(
                    "no such class \"" + cls + "\"; the classes are " + CLASSES);
        return all.stream().filter(c -> c.classes().contains(cls)).limit(limit).toList();
    }

    static Candidate classify(RunRecord run) {
        Set<String> classes = new LinkedHashSet<>();
        String status = "unknown";
        try {
            ObserveReplay.Result r =
                    ObserveReplay.run(
                            new ObserveReplay.Options(
                                    run.script(), run.workflow(), run.inputs(), Set.of()),
                            run.asJournal());
            status = r.status();
            if (status.equals("failed") || status.equals("halted")) classes.add("failed");
        } catch (Exception e) {
            status = "unreadable";
        }
        for (var e : run.journal().entrySet()) {
            String key = e.getKey();
            RunJournal.Entry entry = e.getValue();
            if (entry.kind().equals("failed")) classes.add("diverted");
            if (key.endsWith("#failure")) classes.add("failed");
            if (entry.kind().equals("classify") && unsure(entry.value())) classes.add("unsure");
            if (Set.of("steer", "stop", "level").contains(entry.kind())) classes.add("overridden");
        }
        return new Candidate(run, classes, status);
    }

    private static boolean unsure(Object value) {
        if (!(value instanceof java.util.Map<?, ?> m)) return false;
        for (var e : m.entrySet()) if ("unsure".equals(String.valueOf(e.getValue()))) return true;
        Object c = m.get("confidence");
        return c instanceof Number n && n.doubleValue() < 0.7;
    }

    /** {@code 14d}, {@code 12h} or an ISO date, as the moment it reaches back to. */
    public static Instant since(String text, Instant now) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        if (t.matches("\\d+[dhw]")) {
            long n = Long.parseLong(t.substring(0, t.length() - 1));
            return now.minus(
                    switch (t.charAt(t.length() - 1)) {
                        case 'd' -> Duration.ofDays(n);
                        case 'h' -> Duration.ofHours(n);
                        default -> Duration.ofDays(7 * n);
                    });
        }
        return Instant.parse(t.length() == 10 ? t + "T00:00:00Z" : t);
    }
}
