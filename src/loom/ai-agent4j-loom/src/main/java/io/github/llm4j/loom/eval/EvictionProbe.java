package io.github.llm4j.loom.eval;

import io.github.llm4j.context.ContextCompactor;
import io.github.llm4j.context.ContextCompactors;
import io.github.llm4j.context.ContextManager;
import io.github.llm4j.context.ContextPolicy;
import io.github.llm4j.context.ContextWindow;
import io.github.llm4j.context.Messages;
import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A context-eviction probe. A long run on a small window is simulated with the real context
 * manager: a task, then turn after turn of tool-like output, with a fact planted early. When the
 * window fills the probe checks what the mechanism did: the oldest material went first, the task
 * stayed, and the model was told something was removed, so that no conclusion silently rests on
 * material that is gone. It calls no model, so it proves the mechanism and not what an agent does
 * with a gap; a metamorphic pair (remove the evidence, the conclusion must change) covers that.
 */
public final class EvictionProbe {

    private static final String TASK = "Work out the vault code from the notes below and report it.";
    private static final int MIN_WINDOW_TOKENS = 200;
    private static final int MAX_TURNS = 500;
    private static final Pattern TURN = Pattern.compile("turn (\\d+):");

    private EvictionProbe() {}

    /**
     * What to simulate.
     *
     * @param strategy a compaction strategy that calls no model, such as {@code drop_oldest} or
     *     {@code trim_tool_results}
     * @param windowTokens the model window to simulate
     * @param fact the text planted in one turn
     * @param factAtTurn the turn that carries it (1-based)
     * @param turns how many turns the run has
     * @param turnChars how long each turn's output is
     * @param keepRecent how many of the latest messages compaction must leave alone
     */
    public record Spec(String strategy, int windowTokens, String fact, int factAtTurn, int turns, int turnChars, int keepRecent) {
        public Spec {
            if (strategy == null || strategy.isBlank()) throw new IllegalArgumentException("name a compaction strategy");
            if (fact == null || fact.isBlank()) throw new IllegalArgumentException("the planted fact must not be blank");
            if (windowTokens < MIN_WINDOW_TOKENS) throw new IllegalArgumentException("the window must be at least " + MIN_WINDOW_TOKENS + " tokens, got " + windowTokens);
            if (turns < 2 || turns > MAX_TURNS) throw new IllegalArgumentException("turns must be between 2 and " + MAX_TURNS + ", got " + turns);
            if (factAtTurn < 1 || factAtTurn > turns) throw new IllegalArgumentException("the fact's turn must be between 1 and " + turns + ", got " + factAtTurn);
            if (turnChars < 1) throw new IllegalArgumentException("a turn must have some text, got " + turnChars + " characters");
            if (keepRecent < 0) throw new IllegalArgumentException("keepRecent must not be negative, got " + keepRecent);
        }
    }

    /**
     * What happened.
     *
     * @param evictedAtTurn the turn at which the planted fact was no longer in the conversation,
     *     or null if it never was
     * @param keptFrom the oldest turn still present at the end (0 if none)
     * @param oldestFirst whether what remains is the newest turns, in order, with nothing missing
     *     between them
     * @param modelTold whether a message tells the model that earlier material was removed
     * @param taskKept whether the task is still the first message
     * @param problems what is wrong, empty when the mechanism behaved
     */
    public record Result(Integer evictedAtTurn, int keptFrom, boolean oldestFirst, boolean modelTold, boolean taskKept, List<String> problems) {
        /** Whether the probe ran and found nothing wrong. */
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /**
     * Runs the probe.
     *
     * @throws IllegalArgumentException when the strategy is unknown or needs a model
     */
    public static Result run(Spec spec) {
        if (spec.strategy().equals("summarize"))
            throw new IllegalArgumentException("summarize needs a model and the probe calls none; probe drop_oldest or trim_tool_results, or check what your summaries keep by hand");
        return run(spec, ContextCompactors.byName(spec.strategy(), Map.of()));
    }

    /**
     * Runs the probe against a strategy of your own, which is how a custom compactor is checked
     * for the same three things.
     */
    public static Result run(Spec spec, ContextCompactor compactor) {
        ContextPolicy policy = ContextPolicy.builder().window(ContextWindow.of(spec.windowTokens())).keepRecent(spec.keepRecent()).build();
        ContextManager manager = new ContextManager(policy, compactor, null, 0, null);

        List<Message> history = new ArrayList<>(List.of(Message.user(TASK)));
        Integer evictedAt = null;
        for (int turn = 1; turn <= spec.turns(); turn++) {
            history.add(Message.assistant("turn " + turn + ": looking at the next note."));
            history.add(Message.user("turn " + turn + ": " + (turn == spec.factAtTurn() ? spec.fact() + " " : "") + filler(spec.turnChars())));
            try {
                history = new ArrayList<>(manager.prepare(history, turn).messages());
            } catch (io.github.llm4j.exception.ContextOverflowException e) {
                return new Result(null, 0, false, false, true, List.of("the strategy " + spec.strategy() + " could not make the prompt fit the window at turn " + turn + ": nothing was evicted, so a real run would fail here (" + e.getMessage() + ")"));
            }
            if (evictedAt == null && turn >= spec.factAtTurn() && !holds(history, spec.fact())) evictedAt = turn;
        }

        List<Integer> kept = keptTurns(history);
        boolean oldestFirst = isNewestRun(kept, spec.turns());
        boolean told = history.stream().anyMatch(m -> Messages.compactionOf(m).isPresent());
        boolean taskKept = !history.isEmpty() && TASK.equals(history.get(0).getContent());

        List<String> problems = new ArrayList<>();
        boolean anyRemoved = kept.size() < spec.turns();
        if (evictedAt == null)
            problems.add("the planted fact was never evicted: the window did not fill, or the strategy removes nothing, so the probe tested no eviction (lower the window, add turns, or plant the fact earlier)");
        if (anyRemoved) {
            if (!oldestFirst) problems.add("the oldest material did not go first: the turns that remain are " + kept + " of " + spec.turns());
            if (!told) problems.add("material was evicted and nothing tells the model: it may rest a conclusion on what is gone");
        }
        if (!taskKept) problems.add("the task was evicted: the run no longer knows what it was asked to do");
        return new Result(evictedAt, kept.isEmpty() ? 0 : kept.get(0), oldestFirst, told, taskKept, List.copyOf(problems));
    }

    /** The lines for a report. */
    public static List<String> lines(Spec spec, Result r) {
        List<String> l = new ArrayList<>();
        l.add("fact planted at turn " + spec.factAtTurn() + " of " + spec.turns() + ", window " + spec.windowTokens() + " tokens, strategy " + spec.strategy() + ".");
        l.add(r.evictedAtTurn() == null ? "the fact was never evicted." : "the fact was evicted at turn " + r.evictedAtTurn() + "; the turns kept at the end start at turn " + r.keptFrom() + ".");
        l.add("order: " + (r.oldestFirst() ? "oldest first" : "NOT oldest first") + "; " + (r.modelTold() ? "the model was told" : "the model was NOT told") + "; " + (r.taskKept() ? "task kept" : "task LOST") + ".");
        r.problems().forEach(p -> l.add("problem: " + p));
        return l;
    }

    private static boolean holds(List<Message> history, String fact) {
        return history.stream().anyMatch(m -> m.getContent() != null && m.getContent().contains(fact));
    }

    private static List<Integer> keptTurns(List<Message> history) {
        List<Integer> turns = new ArrayList<>();
        for (Message m : history) {
            if (m.getContent() == null) continue;
            Matcher found = TURN.matcher(m.getContent());
            if (found.find() && m.getRole() == Message.Role.USER) turns.add(Integer.parseInt(found.group(1)));
        }
        return turns;
    }

    /** Whether the turns are the last ones, in order, with none missing: the oldest were removed first. */
    private static boolean isNewestRun(List<Integer> kept, int lastTurn) {
        for (int i = 0; i < kept.size(); i++) if (kept.get(i) != lastTurn - kept.size() + 1 + i) return false;
        return true;
    }

    private static String filler(int chars) {
        String unit = "note about the shipment and its paperwork. ";
        StringBuilder b = new StringBuilder();
        while (b.length() < chars) b.append(unit);
        return b.substring(0, chars);
    }
}
