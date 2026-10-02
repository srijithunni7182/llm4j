package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code rewind to collected when (review.score < 7) at most 2 times carrying feedback = "{review.notes}"}: go back to a checkpoint
 * and run what came after it again, a new attempt that keeps the history of the old one.
 */
public class RewindStmt implements Statement {

    /** What to do about effects already performed in the attempt being discarded. */
    public enum Effects {
        ASK_FIRST("ask first"), KEEP("keep"), REPEAT("repeat");

        private final String phrase;
        Effects(String phrase) { this.phrase = phrase; }
        public String phrase() { return phrase; }

        public static Effects of(String phrase) {
            for (Effects e : values()) if (e.phrase.equals(phrase)) return e;
            return null;
        }
    }

    private final String target;
    private final String condition;
    private final int atMost;
    private final Map<String, String> carrying = new LinkedHashMap<>();
    private Effects effects = Effects.ASK_FIRST;
    private boolean effectsStated;
    private final List<Statement> ifStillFails = new ArrayList<>();
    private final List<Statement> ifBlocked = new ArrayList<>();
    private int line;

    /** @param condition null: always */
    public RewindStmt(String target, String condition, int atMost) {
        this.target = target;
        this.condition = condition;
        this.atMost = atMost;
    }

    public String getTarget() { return target; }
    public String getCondition() { return condition; }
    public int getAtMost() { return atMost; }
    public Map<String, String> getCarrying() { return carrying; }
    public Effects getEffects() { return effects; }
    public boolean isEffectsStated() { return effectsStated; }
    public void setEffects(Effects effects) { this.effects = effects; this.effectsStated = true; }
    public List<Statement> getIfStillFails() { return ifStillFails; }
    public List<Statement> getIfBlocked() { return ifBlocked; }
    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }
}
