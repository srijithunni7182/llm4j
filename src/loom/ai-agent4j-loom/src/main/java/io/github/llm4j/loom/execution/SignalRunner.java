package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.NotifyStmt;
import io.github.llm4j.loom.ast.SignalWaitStmt;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.loom.runtime.Unavailable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The statements of signals that run in the workflow's own executor (phase 34c): a child's {@code
 * wait signal}, and {@code notify parent}. What reaches across to another run (a parent's {@code
 * notify handle}, a parent's {@code wait handle signal}) is {@link ChildLauncher}'s, because it
 * needs the child's journal and the wake.
 *
 * <p>A wait is a journaled step: the entry holds what it returned (the text, or that it timed out),
 * so a replay returns the same thing and never reads the mailbox again, and the "taken" marker that
 * follows it is written again if a crash left it out. With nothing to read, a child holds no thread:
 * it throws {@link RunSuspended} with the reason {@code SIGNAL} and the deadline, and is run again
 * by the parent's {@code notify} or by the launcher's tick.
 */
final class SignalRunner {

    /** Journal kind of a finished signal wait (a read, or a timeout). */
    static final String WAIT = "signal-wait";

    /** Journal kind of a {@code notify}, in the sender's journal: what was sent, or why not. */
    static final String NOTIFY = "notify";

    /** Journal key suffix of a wait's deadline, written once so a restart does not lengthen it. */
    static final String DEADLINE = "#deadline";

    private final HarnessExecutor executor;

    SignalRunner(HarnessExecutor executor) {
        this.executor = executor;
    }

    // ---- notify parent ----

    /** {@code notify parent "name" ["text"] [sticky]}: into this run's own OUT mailbox, for the parent to read. */
    void notifyParent(NotifyStmt stmt) {
        String stepId = executor.currentStepId();
        RunJournal journal = executor.journal();
        if (journal.get(stepId).isPresent()) return; // sent before a restart: not sent again
        HarnessExecutor.Tracked<String> text = executor.tracked(() -> executor.resolve(stmt.getText()));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("to", "parent");
        record.put("name", stmt.getName());
        if (!executor.hasParent()) { // sending to nobody is harmless: waiting for nobody is not
            record.put("delivered", false);
            record.put("reason", "no parent: this run was not started by another, so nobody reads it");
            journal.put(stepId, new RunJournal.Entry(NOTIFY, record));
            return;
        }
        executor.observeExit("notify parent", text.value(), text); // text leaving for another run is an exit
        Mailbox.Signal sent = Mailbox.send(journal, Mailbox.Direction.OUT, stmt.getName(), text.value(), stmt.isSticky(), stepId, executor.nowMillis());
        record.put("delivered", true);
        record.put("seq", sent.seq());
        journal.put(stepId, new RunJournal.Entry(NOTIFY, record));
        executor.audit("signal_sent", Map.of("direction", "to parent", "name", stmt.getName()));
        executor.traceSignal("sent", executor.parentRunId(), stmt.getName(), stmt.isSticky());
        executor.signalParent();
    }

    // ---- wait signal, in a child ----

    /** {@code wait signal "name" timeout T -> text} in a child. */
    void waitForParent(SignalWaitStmt stmt) {
        if (stmt.getTimeoutMillis() == null)
            throw new IllegalStateException("wait signal \"" + stmt.getName() + "\" needs a timeout, so nothing waits for ever: wait signal \"" + stmt.getName() + "\" timeout 10m -> text");
        if (!executor.hasParent())
            throw new IllegalStateException(
                    "wait signal \"" + stmt.getName() + "\": this run has no parent (nothing started it), so no signal can ever arrive and the wait would never end."
                            + " A signal comes from the workflow that started this one; run it with start.");
        String stepId = executor.currentStepId();
        RunJournal journal = executor.journal();
        Optional<RunJournal.Entry> recorded = journal.get(stepId);
        if (recorded.isPresent() && WAIT.equals(recorded.get().kind())) {
            apply(stmt, recorded.get().value(), journal, Mailbox.Direction.IN);
            return;
        }
        Optional<Mailbox.Signal> next = Mailbox.next(journal, Mailbox.Direction.IN, stmt.getName());
        long recorded2 = recordedDeadline(journal, stepId);
        // a signal that came after the wait's deadline is not an answer to it
        if (next.isPresent() && recorded2 > 0 && next.get().atMillis() > recorded2 && executor.nowMillis() >= recorded2) next = Optional.empty();
        if (next.isPresent()) {
            receive(stmt, stepId, journal, Mailbox.Direction.IN, next.get(), "");
            return;
        }
        long deadline = deadlineOf(journal, stepId, stmt.getTimeoutMillis());
        if (executor.nowMillis() >= deadline) {
            timeOut(stmt, stepId);
            return;
        }
        throw RunSuspended.signal(stepId, stmt.getName(), Instant.ofEpochMilli(deadline));
    }

    // ---- shared with the parent's wait (ChildLauncher) ----

    /** The deadline already written for this wait, or 0. */
    long recordedDeadline(RunJournal journal, String stepId) {
        Optional<RunJournal.Entry> had = journal.get(stepId + DEADLINE);
        return had.isPresent() && had.get().value() instanceof Map<?, ?> m && m.get("at") instanceof Number n ? n.longValue() : 0;
    }

    /** When the wait at {@code stepId} gives up: written the first time, so a restart never lengthens it. */
    long deadlineOf(RunJournal journal, String stepId, long timeoutMillis) {
        Optional<RunJournal.Entry> had = journal.get(stepId + DEADLINE);
        if (had.isPresent() && had.get().value() instanceof Map<?, ?> m && m.get("at") instanceof Number n) return n.longValue();
        long at = executor.nowMillis() + timeoutMillis;
        journal.put(stepId + DEADLINE, new RunJournal.Entry("signal-deadline", Map.of("at", at)));
        return at;
    }

    /**
     * Records a read in the reader's journal and binds the text: always {@code unchecked}, because the
     * receiver cannot know what the sender did. The entry is the read; the taken marker follows and is
     * written again on a replay if a crash left it out.
     *
     * @param mailbox the journal that holds the mailbox (the child's own, or the child's seen by its parent)
     * @param stream which child a parent reads from (its run id), or empty in a child
     */
    void receive(SignalWaitStmt stmt, String stepId, RunJournal mailbox, Mailbox.Direction direction, Mailbox.Signal signal, String stream) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", signal.name());
        value.put("text", signal.text());
        value.put("seq", signal.seq());
        value.put("sticky", signal.sticky());
        value.put("stream", stream);
        value.put("timedOut", false);
        executor.journal().put(stepId, new RunJournal.Entry(WAIT, value));
        Mailbox.take(mailbox, direction, signal);
        bindText(stmt, signal.text());
        executor.audit("signal_received", Map.of("name", signal.name(), "sticky", signal.sticky()));
        executor.traceSignal("read", stream.isEmpty() ? executor.parentRunId() : stream, signal.name(), signal.sticky());
    }

    /** The wait's timeout passed with nothing to read: journaled, so a replay times out the same way. */
    void timeOut(SignalWaitStmt stmt, String stepId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", stmt.getName());
        value.put("timedOut", true);
        executor.journal().put(stepId, new RunJournal.Entry(WAIT, value));
        bindNone(stmt);
        executor.audit("signal_timeout", Map.of("name", stmt.getName()));
    }

    /** A replayed wait: what the first run saw, and the taken marker put back if a crash lost it. */
    void apply(SignalWaitStmt stmt, Object recorded, RunJournal mailbox, Mailbox.Direction direction) {
        if (!(recorded instanceof Map<?, ?> m)) return;
        if (Boolean.TRUE.equals(m.get("timedOut"))) {
            bindNone(stmt);
            return;
        }
        String text = String.valueOf(m.get("text"));
        if (m.get("seq") instanceof Number n)
            Mailbox.take(mailbox, direction, new Mailbox.Signal(String.valueOf(m.get("name")), text, n.longValue(), Boolean.TRUE.equals(m.get("sticky")), 0, ""));
        bindText(stmt, text);
    }

    private void bindText(SignalWaitStmt stmt, String text) {
        if (stmt.getResultVariable() == null) return;
        executor.bind(stmt.getResultVariable(), text, Provenance.UNCHECKED); // the sender's words, whatever it knew
        executor.bindSystem("_ok:" + stmt.getResultVariable(), true);
    }

    private void bindNone(SignalWaitStmt stmt) {
        if (stmt.getResultVariable() == null) return;
        executor.bind(stmt.getResultVariable(), Unavailable.noSignal(stmt.getName()), Provenance.NOT_MODEL);
        executor.bindSystem("_ok:" + stmt.getResultVariable(), false);
    }
}
