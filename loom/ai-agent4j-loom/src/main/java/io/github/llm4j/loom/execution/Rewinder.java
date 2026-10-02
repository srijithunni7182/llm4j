package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.CheckpointStmt;
import io.github.llm4j.loom.ast.RewindStmt;
import io.github.llm4j.loom.runtime.ConditionEvaluator;
import io.github.llm4j.loom.runtime.Generations;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs {@code checkpoint} and {@code rewind}: remembers where a run can go back to, decides whether and how it goes back, and records the
 * decision in the journal as a new generation. It never deletes anything; see {@link Generations}.
 *
 * <p>The run's identity rule lives here too: a model call is made again in a new generation, but an effect or a person's answer is
 * identified by where it is and what it is, so an identical one is never repeated.
 */
final class Rewinder {

    /** Where a block of statements is, as the executor walks it: the checkpoints reached in it and the variables at each. */
    static final class Frame {
        final String block;
        final boolean root;
        int index;
        final Map<String, Integer> checkpoints = new HashMap<>();
        final Map<String, Map<String, Object>> snapshots = new HashMap<>();
        final Map<String, Object> memoryMarks = new HashMap<>();

        Frame(String block, boolean root, Map<String, Object> atStart, Object memoryMark) {
            this.block = block;
            this.root = root;
            if (root) {
                snapshots.put("start", atStart);
                memoryMarks.put("start", memoryMark);
            }
        }
    }

    /** Thrown by a {@code rewind} and caught by the block that owns the checkpoint, which goes back to the statement after it. */
    static final class RewindSignal extends RuntimeException {
        final String block;
        final int from;
        final String checkpoint;

        RewindSignal(String block, int from, String checkpoint) {
            super("rewind to " + checkpoint, null, true, false);
            this.block = block;
            this.from = from;
            this.checkpoint = checkpoint;
        }
    }

    private static final int DEFAULT_MAX_REWINDS = 20;

    private final HarnessExecutor run;
    private int maxRewinds = DEFAULT_MAX_REWINDS;

    Rewinder(HarnessExecutor run) {
        this.run = run;
    }

    void setMaxRewinds(int max) {
        this.maxRewinds = max;
    }

    // ---- checkpoint ---------------------------------------------------------------------------------------

    void checkpoint(CheckpointStmt c, Frame frame, int index) {
        for (Map.Entry<String, String> e : c.getStartingWith().entrySet()) run.setVariable(e.getKey(), run.resolve(e.getValue()));
        frame.checkpoints.put(c.getName(), index);
        frame.snapshots.put(c.getName(), new HashMap<>(run.variables()));
        frame.memoryMarks.put(c.getName(), run.memory().mark());
        RunJournal journal = run.journal();
        String key = run.currentStep() + "#checkpoint";
        if (journal.get(key).isEmpty()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", c.getName());
            value.put("generation", generationOfStep());
            value.put("time", run.now().toString());
            journal.put(key, new RunJournal.Entry("checkpoint", value));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checkpoint", c.getName());
        data.put("step", run.currentStep());
        run.audit("checkpoint_reached", data);
        run.trace(TraceEvent.CHECKPOINT, null, "checkpoint " + c.getName(), data);
    }

    private int generationOfStep() {
        String step = run.currentStep();
        int tilde = step.lastIndexOf('~');
        if (tilde < 0) return 1;
        try {
            return Integer.parseInt(step.substring(tilde + 1).replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // ---- rewind -------------------------------------------------------------------------------------------

    void rewind(RewindStmt r, java.util.ArrayDeque<Frame> frames) {
        String stepId = run.currentStep();
        String free = Generations.strip(stepId);
        String decisionKey = stepId + "#rewind";
        RunJournal journal = run.journal();
        Generations generations = run.generations();

        var recorded = journal.get(decisionKey);
        if (recorded.isPresent()) { // a resumed run takes the decision it took before
            String result = String.valueOf(asMap(recorded.get().value()).get("result"));
            if ("exhausted".equals(result)) stillFails(r, free);
            else if ("blocked".equals(result)) run.runHandler(r.getIfBlocked(), "k");
            return;
        }

        boolean bad = r.getCondition() == null || ConditionEvaluator.evaluate(r.getCondition(), run.view());
        if (!bad) {
            decide(decisionKey, "none", null);
            return;
        }
        if (generations.rewindsBy(free) >= r.getAtMost()) {
            decide(decisionKey, "exhausted", null);
            audit("rewind_exhausted", r, free, null);
            stillFails(r, free);
            return;
        }
        if (generations.total() >= maxRewinds) {
            throw new IllegalStateException("The run has gone back " + generations.total() + " times, which is the most it may (" + maxRewinds
                    + "). Busiest rewinds: " + busiest(generations) + ".");
        }

        Frame target = find(frames, r.getTarget());
        if (target == null) throw new IllegalStateException("rewind to " + r.getTarget() + ": that checkpoint has not been reached (it must come earlier, in this block or one around it)");
        int from = target.root && r.getTarget().equals("start") && !target.checkpoints.containsKey("start") ? 0 : target.checkpoints.get(r.getTarget()) + 1;

        RewindStmt.Effects policy = r.getEffects();
        List<String> blockers = effectsIn(target.block, from);
        if (!blockers.isEmpty() && policy == RewindStmt.Effects.ASK_FIRST) {
            if (!r.getIfBlocked().isEmpty()) {
                decide(decisionKey, "blocked", blockers);
                audit("rewind_blocked", r, free, blockers);
                run.runHandler(r.getIfBlocked(), "k");
                return;
            }
            String answer = askPerson(stepId + "#rewind-blocked", r, blockers);
            switch (answer) {
                case "keep" -> policy = RewindStmt.Effects.KEEP;
                case "repeat" -> policy = RewindStmt.Effects.REPEAT;
                default -> {
                    decide(decisionKey, "none", null);
                    return;
                }
            }
        }

        Map<String, String> carried = new LinkedHashMap<>();
        r.getCarrying().forEach((name, value) -> carried.put(name, run.resolve(value)));
        String reason = r.getCondition() == null ? "always" : r.getCondition();
        Generations.Boundary b = generations.start(target.block, from, r.getTarget(), "script", free, reason, carried, policy.phrase(), false);
        audit("run_rewound", r, free, null);
        List<String> approved = io.github.llm4j.loom.runtime.EffectScan.approvalsIn(run.journal(), target.block, from);
        run.trace(TraceEvent.REWIND, null, "rewind to " + r.getTarget() + " (generation " + b.generation() + "): " + reason
                + (approved.isEmpty() ? "" : "; approved calls that ran and will not be asked again: " + String.join(", ", approved)),
                Map.of("generation", b.generation(), "checkpoint", r.getTarget()));
        throw new RewindSignal(target.block, from, r.getTarget());
    }

    private void stillFails(RewindStmt r, String free) {
        if (r.getIfStillFails().isEmpty()) {
            throw new IllegalStateException("A rewind (" + free + ") still fails after " + r.getAtMost() + " time(s) going back to " + r.getTarget() + ".");
        }
        run.runHandler(r.getIfStillFails(), "w");
    }

    private void decide(String key, String result, List<String> blockers) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("result", result);
        if (blockers != null) value.put("blockers", blockers);
        run.journal().put(key, new RunJournal.Entry("rewind-decision", value));
    }

    private static Frame find(java.util.ArrayDeque<Frame> frames, String name) {
        for (Frame f : frames) { // innermost first
            if (f.snapshots.containsKey(name)) return f;
        }
        return null;
    }

    /** What a person is asked when the rewind would cross effects already performed. */
    private String askPerson(String key, RewindStmt r, List<String> blockers) {
        RunJournal journal = run.journal();
        String answer = journal.get(key).map(e -> String.valueOf(e.value())).orElse(null);
        if (answer == null) {
            if (run.humanInterface() == null) throw new IllegalStateException("A rewind is held until a person says what to do about " + blockers + ", but no HumanInterface is set.");
            String question = "Going back to " + r.getTarget() + " would cross these side effects that already happened: " + String.join("; ", blockers)
                    + ". Answer: keep (don't repeat identical ones), repeat (do them all again) or cancel (don't go back).";
            answer = run.humanInterface().promptHuman(key, question); // may pause the run
            journal.put(key, new RunJournal.Entry("human", answer));
        }
        String a = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
        return a.startsWith("keep") ? "keep" : a.startsWith("repeat") ? "repeat" : "cancel";
    }

    // ---- effects in the region that would be discarded -----------------------------------------------------

    /** The effects performed (or that may have been) in the statements from {@code from} on in a block, any attempt. */
    List<String> effectsIn(String block, int from) {
        return io.github.llm4j.loom.runtime.EffectScan.effectsIn(run.journal(), block, from);
    }

    private static String busiest(Generations g) {
        Map<String, Integer> counts = new HashMap<>();
        for (Generations.Boundary b : g.all()) counts.merge(b.statement() == null ? b.by() : b.statement(), 1, Integer::sum);
        return counts.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(3)
                .map(e -> e.getKey() + " x" + e.getValue()).collect(java.util.stream.Collectors.joining(", "));
    }

    private void audit(String event, RewindStmt r, String statement, List<String> blockers) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("statement", statement);
        data.put("checkpoint", r.getTarget());
        data.put("generation", run.generations().total() + 1);
        if (blockers != null) data.put("effects", blockers);
        run.audit(event, data);
    }

    // ---- going back, as the owning block sees it -----------------------------------------------------------

    /** The variables as they were at the checkpoint a rewind went back to. */
    void restore(Frame frame, String checkpoint) {
        Map<String, Object> snapshot = frame.snapshots.get(checkpoint);
        if (snapshot == null) return;
        for (String name : new ArrayList<>(run.variables().keySet())) if (!snapshot.containsKey(name)) run.removeVariable(name);
        snapshot.forEach(run::setVariable);
        run.memory().restore(frame.memoryMarks.get(checkpoint));
    }

    /** Applies what a new generation carries in, when the run reaches the first statement of it (live or after a resume). */
    void begin(String block, int index) {
        Generations.Boundary b = run.generations().startingAt(block, index);
        if (b == null) return;
        b.carried().forEach(run::setVariable);
        run.setVariable("_rewind", String.valueOf(run.generations().rewindsBy(b.statement() == null ? "" : b.statement())));
        run.setVariable("_rewindReason", b.reason() == null ? "" : b.reason());
        run.setVariable("_rewindTo", b.name() == null ? "" : b.name());
    }

    // ---- people ---------------------------------------------------------------------------------------------

    /**
     * What a person already answered to this question at this place, in any attempt, or null. The same question in the same place is
     * not asked again after a rewind; a changed question is.
     */
    String recordedAnswer(String resolvedQuestion) {
        RunJournal journal = run.journal();
        String raw = run.currentStep();
        String identity = run.identityStep();
        var own = journal.get(raw);
        if (own.isPresent()) return String.valueOf(own.get().value()); // this attempt asked it, or generation 1 did
        if (!run.rewindsUsed() || raw.equals(identity)) return null;
        if (run.generations().asksAgain(raw)) return null; // an operator asked for the people to be asked again
        var earlier = journal.get(identity);
        if (earlier.isEmpty()) return null;
        var asked = journal.get(identity + "#asked");
        if (asked.isEmpty() || String.valueOf(asked.get().value()).equals(hash(resolvedQuestion))) return String.valueOf(earlier.get().value());
        return null;
    }

    void recordAnswer(String resolvedQuestion, String answer) {
        RunJournal journal = run.journal();
        String raw = run.currentStep();
        String identity = run.identityStep();
        if (!run.rewindsUsed() || journal.get(identity).isEmpty() || raw.equals(identity)) {
            journal.put(raw, new RunJournal.Entry("human", answer));
            if (run.rewindsUsed() && raw.equals(identity)) journal.put(identity + "#asked", new RunJournal.Entry("asked", hash(resolvedQuestion)));
        } else {
            journal.put(raw, new RunJournal.Entry("human", answer)); // a changed question: its own record; the earlier one stays
        }
    }

    private static String hash(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
