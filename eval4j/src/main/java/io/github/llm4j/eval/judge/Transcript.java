package io.github.llm4j.eval.judge;

import io.github.llm4j.agent.AgentResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A multi-turn conversation to be judged as a whole by {@link ConversationJudgeCondition}. Build
 * one from the user inputs and the {@link AgentResult}s an agent produced, or turn by turn with
 * {@link #builder()}.
 */
public final class Transcript {

    public enum Role {
        USER,
        ASSISTANT
    }

    /**
     * One message; {@code trajectory} is the agent's tool-use steps for an assistant turn, if any.
     */
    public record Turn(Role role, String content, String trajectory) {}

    /** A rendered slice of the conversation, and how many earlier turns were left out. */
    record Rendered(String text, int omittedTurns) {}

    private static final String TRUNCATED = "[... truncated]";

    private final List<Turn> turns;

    private Transcript(List<Turn> turns) {
        this.turns = Collections.unmodifiableList(new ArrayList<>(turns));
    }

    /**
     * Pairs each user input with the agent's reply. An incomplete result becomes an empty assistant
     * turn, which the metrics count as a failure of that turn.
     */
    public static Transcript fromResults(List<String> userInputs, List<AgentResult> results) {
        Objects.requireNonNull(userInputs, "userInputs cannot be null");
        Objects.requireNonNull(results, "results cannot be null");
        if (userInputs.size() != results.size()) {
            throw new IllegalArgumentException(
                    "Expected one user input per result but got "
                            + userInputs.size()
                            + " inputs and "
                            + results.size()
                            + " results");
        }
        Builder builder = builder();
        for (int i = 0; i < results.size(); i++) {
            AgentResult result = results.get(i);
            builder.user(userInputs.get(i));
            String answer = result.isCompleted() ? result.getFinalAnswer() : null;
            builder.assistant(
                    answer == null ? "" : answer, OutputExtractor.extractTrajectory(result));
        }
        return builder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Turn> turns() {
        return turns;
    }

    /** Indices (into {@link #turns()}) of the assistant turns, in order. */
    List<Integer> assistantIndices() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            if (turns.get(i).role() == Role.ASSISTANT) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * Number of user turns strictly before {@code turnIndex}'s exchange, i.e. its 1-based number.
     */
    int exchangeNumber(int assistantTurnIndex) {
        int n = 0;
        for (int i = 0; i <= assistantTurnIndex; i++) {
            if (turns.get(i).role() == Role.ASSISTANT) {
                n++;
            }
        }
        return n;
    }

    /**
     * Renders turns {@code [from, to)} as labelled lines, keeping the most recent turns that fit in
     * {@code maxChars}. Omitted earlier turns are noted, and a single over-long turn is truncated
     * with an explicit marker — nothing is dropped silently.
     */
    Rendered render(int from, int to, int maxChars) {
        List<String> lines = new ArrayList<>();
        int used = 0;
        int included = 0;
        for (int i = to - 1; i >= from; i--) {
            Turn t = turns.get(i);
            String label = t.role() == Role.USER ? "USER" : "ASSISTANT";
            String text = t.content() == null ? "" : t.content();
            String line = label + " (turn " + turnNumber(i) + "): " + text;
            int remaining = maxChars - used;
            if (line.length() > remaining) {
                if (included == 0 && remaining > TRUNCATED.length()) {
                    line = line.substring(0, remaining - TRUNCATED.length()) + TRUNCATED;
                } else {
                    break;
                }
            }
            lines.add(line);
            used += line.length() + 1;
            included++;
        }
        Collections.reverse(lines);
        int omitted = (to - from) - included;
        StringBuilder sb = new StringBuilder();
        if (omitted > 0) {
            sb.append("[").append(omitted).append(" earlier turn(s) omitted]\n");
        }
        sb.append(String.join("\n", lines));
        return new Rendered(sb.toString(), omitted);
    }

    /** 1-based exchange number shown in labels: a user turn and its reply share a number. */
    private int turnNumber(int index) {
        int n = 0;
        for (int i = 0; i <= index; i++) {
            if (turns.get(i).role() == Role.USER) {
                n++;
            }
        }
        return Math.max(n, 1);
    }

    public static final class Builder {
        private final List<Turn> turns = new ArrayList<>();

        public Builder user(String content) {
            turns.add(new Turn(Role.USER, content, null));
            return this;
        }

        public Builder assistant(String content) {
            turns.add(new Turn(Role.ASSISTANT, content, null));
            return this;
        }

        Builder assistant(String content, String trajectory) {
            turns.add(new Turn(Role.ASSISTANT, content, trajectory));
            return this;
        }

        public Transcript build() {
            return new Transcript(turns);
        }
    }
}
