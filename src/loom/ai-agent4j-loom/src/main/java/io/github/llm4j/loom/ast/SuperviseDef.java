package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code supervise Researcher { when looping(3) { steer "…" once }  when context > 90% { wrap_up } }}: rules that watch one agent while it works and act when a
 * condition holds. Symbolic and deterministic, never a model: every signal is counted from what the agent does.
 */
public class SuperviseDef implements Node {

    /** What a rule watches. */
    public enum Signal {
        /** The same tool with the same input, {@code n} times in a row. */
        LOOPING("looping"),
        /** A tool failing {@code n} times in a row. */
        TOOL_FAILING("tool_failing"),
        /** The prompt filling more than {@code n} percent of the model's window (when the window is known). */
        CONTEXT("context"),
        /** More than {@code n} model calls in one step. */
        STEPS("steps");

        private final String word;
        Signal(String word) { this.word = word; }
        public String word() { return word; }
    }

    /** What a rule does. */
    public enum Do { STEER, WRAP_UP, CANCEL }

    /** One thing a rule does: a steer with its text, a wrap-up, or a cancel with the outcome it names (or null: the script's defaults decide). */
    public record Action(Do kind, String text, OutcomeStmt.Kind outcome) { }

    /** One rule. {@code tool} is set for {@code tool_failing}; {@code once} means it never fires a second time in the same step. */
    public static class Rule {
        private final Signal signal;
        private final String tool;
        private final int n;
        private final boolean once;
        private final int line;
        private final List<Action> actions = new ArrayList<>();

        public Rule(Signal signal, String tool, int n, boolean once, int line) {
            this.signal = signal;
            this.tool = tool;
            this.n = n;
            this.once = once;
            this.line = line;
        }

        public Signal getSignal() { return signal; }
        public String getTool() { return tool; }
        public int getN() { return n; }
        public boolean isOnce() { return once; }
        public int getLine() { return line; }
        public List<Action> getActions() { return actions; }

        /** The rule as it is written in a script, for messages. */
        public String describe() {
            return switch (signal) {
                case LOOPING -> "looping(" + n + ")";
                case TOOL_FAILING -> "tool_failing(" + tool + ", " + n + ")";
                case CONTEXT -> "context > " + n + "%";
                case STEPS -> "steps > " + n;
            };
        }
    }

    private final String agent;
    private final int line;
    private final List<Rule> rules = new ArrayList<>();

    public SuperviseDef(String agent, int line) {
        this.agent = agent;
        this.line = line;
    }

    public String getAgent() { return agent; }
    public int getLine() { return line; }
    public List<Rule> getRules() { return rules; }
}
