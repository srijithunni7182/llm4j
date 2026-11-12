package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.runtime.Generations;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A run's mailbox (phase 34c, D8, D9 and D13): signals are entries in the journal of the run
 * that <em>owns</em> the mailbox, so a signal sent before anyone waits is not lost, a crash never
 * delivers one twice, and reading is journaled where it happens. Every child run has two
 * mailboxes in its journal: {@link Direction#IN} (a parent signals the child) and {@link
 * Direction#OUT} (the child signals its parent, which reads it through the handle).
 *
 * <p>A <b>message</b> is consumed once, in the order sent, per name. A <b>sticky</b> signal is a
 * latch: it stays set, any reader sees it without consuming it, and a later send of the same name
 * replaces it. A signal belongs to an <b>epoch</b>, the number of times the child has been sent
 * back to an earlier checkpoint; one of an older epoch is history and is never delivered, so a
 * rewound child starts with an empty mailbox. Consumption is a "taken" marker in the same journal,
 * written idempotently; the reader's own journaled step is what makes the read happen once.
 *
 * <p>Everything is bounded and fails loudly (D13): 16 pending messages per name, 64 in all, a name
 * of at most 40 plain characters and a payload of at most 2000. All writes to one journal are
 * serialised, so concurrent senders neither lose a message nor pass a limit.
 */
final class Mailbox {

    /** Most pending messages of one name. */
    static final int MAX_PER_NAME = 16;

    /** Most pending signals in one mailbox. */
    static final int MAX_TOTAL = 64;

    /** Longest name. */
    static final int MAX_NAME = 40;

    /** Longest payload, the size of a steer ({@code InterventionCoordinator.MAX_STEER_CHARS}). */
    static final int MAX_PAYLOAD = 2000;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]+");

    /** Which of a run's two mailboxes. */
    enum Direction {
        /** Signals to the run, from the run that started it. */
        IN("in"),
        /** Signals from the run, to the run that started it. */
        OUT("out");

        private final String word;

        Direction(String word) {
            this.word = word;
        }

        String word() {
            return word;
        }
    }

    /** One signal as the journal holds it. */
    record Signal(String name, String text, long seq, boolean sticky, int epoch, String from) {}

    private Mailbox() {}

    private static String key(Direction d, String name, long seq) {
        return "#sig." + d.word() + "." + name + "." + seq;
    }

    private static String takenKey(Direction d, String name, long seq) {
        return "#sig.take." + d.word() + "." + name + "." + seq;
    }

    /** The epoch of a journal: how many times its run has been sent back. */
    static int epoch(RunJournal journal) {
        return new Generations(journal).total();
    }

    /**
     * Writes a signal into the mailbox. A send from a step that already sent returns what it sent and
     * writes nothing, so a replayed sender never sends twice.
     *
     * @param fromStep the sender's step id: what makes the send idempotent
     * @throws IllegalStateException for a name or payload that is not allowed, or a mailbox that is
     *     full; the message says which limit
     */
    static Signal send(RunJournal journal, Direction direction, String name, String text, boolean sticky, String fromStep) {
        validate(name, text);
        String payload = text == null ? "" : text;
        synchronized (journal) {
            List<Signal> existing = all(journal, direction);
            for (Signal s : existing) if (fromStep.equals(s.from()) && name.equals(s.name())) return s;
            int epoch = epoch(journal);
            if (!sticky) {
                if (pending(journal, direction, name) >= MAX_PER_NAME)
                    throw new IllegalStateException(
                            "the mailbox already holds " + MAX_PER_NAME + " signals named " + name + " that have not been read,"
                                    + " the most it may. Let the receiver read some, or send fewer.");
                if (pendingTotal(journal, direction) >= MAX_TOTAL)
                    throw new IllegalStateException(
                            "the mailbox already holds " + MAX_TOTAL + " signals that have not been read, the most it may."
                                    + " Let the receiver read some, or send fewer.");
            } else if (latch(journal, direction, name) == null && pendingTotal(journal, direction) >= MAX_TOTAL) {
                throw new IllegalStateException(
                        "the mailbox already holds " + MAX_TOTAL + " signals that have not been read, the most it may.");
            }
            long seq = existing.stream().mapToLong(Signal::seq).max().orElse(0) + 1;
            Signal signal = new Signal(name, payload, seq, sticky, epoch, fromStep);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", name);
            value.put("text", payload);
            value.put("seq", seq);
            value.put("sticky", sticky);
            value.put("epoch", epoch);
            value.put("from", fromStep);
            journal.put(key(direction, name, seq), new RunJournal.Entry("signal", value));
            return signal;
        }
    }

    /** What is wrong with a signal name, or null when it is allowed (also used by {@code weave check}). */
    static String nameProblem(String name) {
        if (name == null || name.isEmpty() || !NAME.matcher(name).matches())
            return "a signal's name is a plain word (letters, digits and _), and \"" + name + "\" is not";
        if (name.length() > MAX_NAME)
            return "a signal's name is at most " + MAX_NAME + " characters, and this one has " + name.length();
        return null;
    }

    private static void validate(String name, String text) {
        if (name == null || name.isEmpty() || !NAME.matcher(name).matches())
            throw new IllegalStateException(
                    "a signal's name is a plain word (letters, digits and _), and \"" + name + "\" is not");
        if (name.length() > MAX_NAME)
            throw new IllegalStateException("a signal's name is at most " + MAX_NAME + " characters, and this one has " + name.length());
        if (text != null && text.length() > MAX_PAYLOAD)
            throw new IllegalStateException(
                    "a signal's text is at most " + MAX_PAYLOAD + " characters, and this one has " + text.length()
                            + ". Send less, or put the data somewhere both can read and signal where it is.");
    }

    /** Every signal in the mailbox, every epoch, oldest first. */
    static List<Signal> all(RunJournal journal, Direction direction) {
        String prefix = "#sig." + direction.word() + ".";
        List<Signal> out = new ArrayList<>();
        for (Map.Entry<String, RunJournal.Entry> e : journal.all().entrySet()) {
            if (!e.getKey().startsWith(prefix) || !"signal".equals(e.getValue().kind())) continue;
            if (!(e.getValue().value() instanceof Map<?, ?> m)) continue;
            out.add(
                    new Signal(
                            String.valueOf(m.get("name")),
                            String.valueOf(m.get("text")),
                            ((Number) m.get("seq")).longValue(),
                            Boolean.TRUE.equals(m.get("sticky")),
                            ((Number) m.get("epoch")).intValue(),
                            String.valueOf(m.get("from"))));
        }
        out.sort(Comparator.comparingLong(Signal::seq));
        return out;
    }

    private static boolean isTaken(RunJournal journal, Direction d, Signal s) {
        return journal.get(takenKey(d, s.name(), s.seq())).isPresent();
    }

    /** The latest sticky signal of this name in the current epoch, or null. */
    private static Signal latch(RunJournal journal, Direction d, String name) {
        int epoch = epoch(journal);
        Signal best = null;
        for (Signal s : all(journal, d)) if (s.sticky() && s.epoch() == epoch && s.name().equals(name)) best = s;
        return best;
    }

    /**
     * What a reader of {@code name} gets next: the oldest message not yet taken, else the latch if
     * one is set, else nothing. Only the current epoch counts.
     */
    static Optional<Signal> next(RunJournal journal, Direction direction, String name) {
        int epoch = epoch(journal);
        Signal latch = null;
        for (Signal s : all(journal, direction)) {
            if (s.epoch() != epoch || !s.name().equals(name)) continue;
            if (s.sticky()) latch = s; // the last one wins
            else if (!isTaken(journal, direction, s)) return Optional.of(s);
        }
        return Optional.ofNullable(latch);
    }

    /** Marks a message as read. A latch is not consumed by reading, so this does nothing for one. Idempotent. */
    static void take(RunJournal journal, Direction direction, Signal signal) {
        if (signal.sticky()) return;
        String key = takenKey(direction, signal.name(), signal.seq());
        synchronized (journal) {
            if (journal.get(key).isEmpty())
                journal.put(key, new RunJournal.Entry("signal-take", Map.of("name", signal.name(), "seq", signal.seq())));
        }
    }

    /** How many messages of this name are waiting to be read, this epoch. */
    static int pending(RunJournal journal, Direction direction, String name) {
        int epoch = epoch(journal);
        int n = 0;
        for (Signal s : all(journal, direction))
            if (!s.sticky() && s.epoch() == epoch && s.name().equals(name) && !isTaken(journal, direction, s)) n++;
        return n;
    }

    /** How many signals are waiting in all: messages not yet read, and one for each name with a latch. */
    static int pendingTotal(RunJournal journal, Direction direction) {
        int epoch = epoch(journal);
        int n = 0;
        java.util.Set<String> latched = new java.util.HashSet<>();
        for (Signal s : all(journal, direction)) {
            if (s.epoch() != epoch) continue;
            if (s.sticky()) latched.add(s.name());
            else if (!isTaken(journal, direction, s)) n++;
        }
        return n + latched.size();
    }
}
