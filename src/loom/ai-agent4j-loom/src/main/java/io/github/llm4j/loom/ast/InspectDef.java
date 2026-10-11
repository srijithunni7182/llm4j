package io.github.llm4j.loom.ast;

import java.time.Duration;

/**
 * What an agent's {@code inspect { }} block says is normal for it, so the loom-inspect console knows when to raise an incident. It
 * changes nothing about a run: no behaviour, no budget, no identity (an agent's earned autonomy is not reset by editing it). A rule left
 * out keeps the console's default. Every number is the line where it was written, for {@code weave check}.
 */
public final class InspectDef {

    private int line;
    private Integer sameAction;
    private Integer sameOutput;
    private Integer toolFailures;
    private Integer ungroundedWarn;
    private Integer ungroundedHigh;
    private Duration pausedLongerThan;
    private Integer noProgressRounds;
    private String noProgressVariable;
    private int noProgressLine;
    private int ungroundedLine;

    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    /** The same tool call this many times in one step (rounds of a loop together), or null for the default. */
    public Integer getSameAction() { return sameAction; }
    public void setSameAction(Integer n) { this.sameAction = n; }

    /** The same answer this many times in a row from one step. */
    public Integer getSameOutput() { return sameOutput; }
    public void setSameOutput(Integer n) { this.sameOutput = n; }

    /** A tool failing this many times in a row. */
    public Integer getToolFailures() { return toolFailures; }
    public void setToolFailures(Integer n) { this.toolFailures = n; }

    /** Figures in an answer found in nothing the step saw: warn at this many (null: default). */
    public Integer getUngroundedWarn() { return ungroundedWarn; }
    public void setUngroundedWarn(Integer n) { this.ungroundedWarn = n; }

    /** ... and high at this many. */
    public Integer getUngroundedHigh() { return ungroundedHigh; }
    public void setUngroundedHigh(Integer n) { this.ungroundedHigh = n; }

    public int getUngroundedLine() { return ungroundedLine; }
    public void setUngroundedLine(int line) { this.ungroundedLine = line; }

    /** A pause longer than this is raised from information to a warning. */
    public Duration getPausedLongerThan() { return pausedLongerThan; }
    public void setPausedLongerThan(Duration d) { this.pausedLongerThan = d; }

    /** The named workflow variable unchanged at the end of this many loop rounds in a row. */
    public Integer getNoProgressRounds() { return noProgressRounds; }
    /** The variable (or {@code variable.field}) that says what progress is. */
    public String getNoProgressVariable() { return noProgressVariable; }
    public int getNoProgressLine() { return noProgressLine; }

    public void setNoProgress(int rounds, String variable, int line) {
        this.noProgressRounds = rounds;
        this.noProgressVariable = variable;
        this.noProgressLine = line;
    }

    /** True when the block sets nothing. */
    public boolean isEmpty() {
        return sameAction == null && sameOutput == null && toolFailures == null && ungroundedWarn == null && ungroundedHigh == null
                && pausedLongerThan == null && noProgressRounds == null;
    }
}
