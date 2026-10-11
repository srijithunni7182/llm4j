package io.github.llm4j.loom.ast;

import io.github.llm4j.loom.autonomy.expr.Expr;

/** One step of a ladder: what the runtime enforces there (its mode), and the conditions to move up into it, down out of it, and to ask a person. */
public class RungDef implements Node {

    /** What the runtime enforces at a rung. Closed on purpose: a ladder cannot invent a mode or pretend to be blind. */
    public enum Mode {
        /** A person responds without seeing the proposal. */
        BLIND("blind"),
        /** A person sees the proposal and accepts, edits or rejects it. */
        CONFIRM("confirm"),
        /** The proposal takes effect, except for sampled and escalated cases. */
        AUTONOMOUS("autonomous");

        private final String word;

        Mode(String word) { this.word = word; }

        public String word() { return word; }

        public static Mode of(String word) {
            for (Mode m : values()) if (m.word.equals(word)) return m;
            return null;
        }
    }

    private final String name;
    private final int line;
    private Mode mode;
    private int modeLine;
    private Expr climb;
    private String climbText;
    private int climbLine;
    private Expr slip;
    private String slipText;
    private String slipTo;
    private int slipLine;
    private double samplePercent;
    private int sampleLine;
    private Expr escalate;
    private String escalateText;
    private int escalateLine;

    public RungDef(String name, int line) {
        this.name = name;
        this.line = line;
    }

    public String getName() { return name; }
    public int getLine() { return line; }
    public Mode getMode() { return mode; }
    public int getModeLine() { return modeLine; }
    public void setMode(Mode mode, int line) { this.mode = mode; this.modeLine = line; }

    /** The condition to move up into this rung, or null (the first rung has none). */
    public Expr getClimb() { return climb; }
    public String getClimbText() { return climbText; }
    public int getClimbLine() { return climbLine; }
    public void setClimb(Expr climb, String text, int line) { this.climb = climb; this.climbText = text; this.climbLine = line; }

    /** The condition to move down out of this rung, or null. */
    public Expr getSlip() { return slip; }
    public String getSlipText() { return slipText; }
    /** The rung to slip to, or null for the one below. */
    public String getSlipTo() { return slipTo; }
    public int getSlipLine() { return slipLine; }
    public void setSlip(Expr slip, String text, String to, int line) { this.slip = slip; this.slipText = text; this.slipTo = to; this.slipLine = line; }

    /** The share (0 to 1) of cases at this rung sent to a person blind; 0 for none. */
    public double getSamplePercent() { return samplePercent; }
    public int getSampleLine() { return sampleLine; }
    public void setSample(double share, int line) { this.samplePercent = share; this.sampleLine = line; }

    /** The condition that sends a case to a person whatever the rung, or null. */
    public Expr getEscalate() { return escalate; }
    public String getEscalateText() { return escalateText; }
    public int getEscalateLine() { return escalateLine; }
    public void setEscalate(Expr escalate, String text, int line) { this.escalate = escalate; this.escalateText = text; this.escalateLine = line; }
}
