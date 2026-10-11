package io.github.llm4j.loom.ast;

import io.github.llm4j.loom.autonomy.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A declared decision: what an agent proposes, who decides, and the rules by which the agent earns (and loses) freedom. Written in plain
 * phrases so a risk owner can read it aloud; see the "Earned autonomy" section of the guide.
 */
public class DecisionDef implements Node {

    /** What happens when the agent behind a decision changes. */
    public enum OnChange {
        START_OVER("start over"), TEST_ON_PAST("test it on past cases"), KEEP_TRUST("keep the trust");
        private final String phrase;
        OnChange(String phrase) { this.phrase = phrase; }
        public String phrase() { return phrase; }
    }

    /** {@code to suggest: after 100 cases over 14 days, agreeing at least 90%, with no dangerous mistakes}. */
    public record UpRule(Level to, int cases, int days, double agreeingAtLeast, boolean noDangerous, Double dangerousAtMost, int line) { }

    /** What a demotion rule counts. */
    public enum Count { DANGEROUS_MISTAKES, REVERSALS, UNUSABLE_PROPOSALS, AGREEMENT_BELOW }

    /** {@code drop to suggest when 2 dangerous mistakes in 50 cases} or {@code drop to suggest when agreement falls below 92%}. */
    public record DropRule(Level to, Count count, int n, int inCases, double percent, int line) { }

    /** {@code dangerous mistake: propose approve, person decides reject}. */
    public record Mistake(String proposed, String decided) { }

    private final String name;
    private int line;
    private String agent;
    private final List<String> choices = new ArrayList<>();
    private String groupBy;
    private final List<String> remember = new ArrayList<>();
    private final Map<String, String> rememberTypes = new LinkedHashMap<>();
    private final List<Mistake> dangerous = new ArrayList<>();
    private String ask;
    private int keepDays;
    private OnChange onChange = OnChange.START_OVER;
    private boolean onChangeStated;
    private String tellTool;
    private String task;
    private int staleDays = 7;

    private Level startAt = Level.WATCH;
    private Level ceiling = Level.SUGGEST;
    private final Map<Level, UpRule> upRules = new LinkedHashMap<>();
    private int window = 100;
    private double auditPercent;
    private final List<String> askWhen = new ArrayList<>();
    private int askAfterPerDay;
    private final List<DropRule> dropRules = new ArrayList<>();
    private String approver;
    private boolean automatic;
    private boolean trustGiven;
    private boolean ceilingStated;

    private String ladder;
    private int ladderLine;
    private final Map<String, io.github.llm4j.loom.autonomy.expr.Expr> ladderArgs = new LinkedHashMap<>();
    private String ceilingRung;
    private int ceilingRungLine;
    private String proposes;
    private int proposesLine;
    private boolean ladderFromAgent;
    private boolean ladderFromTrust;
    private final List<MeasureDef> trustMeasures = new ArrayList<>();
    private final Map<String, String> trustLabels = new LinkedHashMap<>();

    public DecisionDef(String name) { this.name = name; }

    public String getName() { return name; }
    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }
    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }
    public List<String> getChoices() { return choices; }
    public String getGroupBy() { return groupBy; }
    public void setGroupBy(String groupBy) { this.groupBy = groupBy; }
    public List<String> getRemember() { return remember; }
    /** The declared type of a remembered value ({@code remember: amount: number}): number, text or flag; absent when none was declared. */
    public Map<String, String> getRememberTypes() { return rememberTypes; }
    public List<Mistake> getDangerous() { return dangerous; }
    public String getAsk() { return ask; }
    public void setAsk(String ask) { this.ask = ask; }
    public int getKeepDays() { return keepDays; }
    public void setKeepDays(int keepDays) { this.keepDays = keepDays; }
    public OnChange getOnChange() { return onChange; }
    public void setOnChange(OnChange onChange) { this.onChange = onChange; this.onChangeStated = true; }
    /** True when the script wrote a {@code when the agent changes:} line. */
    public boolean isOnChangeStated() { return onChangeStated; }
    public String getTellTool() { return tellTool; }
    public void setTellTool(String tellTool) { this.tellTool = tellTool; }
    /** The text asked of the agent for each case, with {name} placeholders for the remembered values; null for the default. */
    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }
    public int getStaleDays() { return staleDays; }
    public void setStaleDays(int staleDays) { this.staleDays = staleDays; }

    public Level getStartAt() { return startAt; }
    public void setStartAt(Level startAt) { this.startAt = startAt; }
    public Level getCeiling() { return ceiling; }
    public boolean isCeilingStated() { return ceilingStated; }
    public void setCeiling(Level ceiling) { this.ceiling = ceiling; this.ceilingStated = true; }
    public Map<Level, UpRule> getUpRules() { return upRules; }
    public int getWindow() { return window; }
    public void setWindow(int window) { this.window = window; }
    public double getAuditPercent() { return auditPercent; }
    public void setAuditPercent(double auditPercent) { this.auditPercent = auditPercent; }
    public List<String> getAskWhen() { return askWhen; }
    public int getAskAfterPerDay() { return askAfterPerDay; }
    public void setAskAfterPerDay(int askAfterPerDay) { this.askAfterPerDay = askAfterPerDay; }
    public List<DropRule> getDropRules() { return dropRules; }
    public String getApprover() { return approver; }
    public void setApprover(String approver) { this.approver = approver; }
    public boolean isAutomatic() { return automatic; }
    public void setAutomatic(boolean automatic) { this.automatic = automatic; }
    /** True when the script wrote a {@code trust { }} block. */
    public boolean isTrustGiven() { return trustGiven; }
    public void setTrustGiven(boolean trustGiven) { this.trustGiven = trustGiven; }

    /** The ladder this decision binds ({@code ladder: Name}), or null when it has its own {@code trust} block. */
    public String getLadder() { return ladder; }
    public int getLadderLine() { return ladderLine; }
    public Map<String, io.github.llm4j.loom.autonomy.expr.Expr> getLadderArgs() { return ladderArgs; }
    public void setLadder(String ladder, int line) { this.ladder = ladder; this.ladderLine = line; }
    /** True when the ladder came from the proposing agent's default and not from this decision. */
    public boolean isLadderFromAgent() { return ladderFromAgent; }
    public void setLadderFromAgent(boolean fromAgent) { this.ladderFromAgent = fromAgent; }
    /** True when the ladder is the one written from this decision's {@code trust { }} block (or from its having none). */
    public boolean isLadderFromTrust() { return ladderFromTrust; }
    public void setLadderFromTrust(boolean fromTrust) { this.ladderFromTrust = fromTrust; }
    /** What each condition of the ladder written from {@code trust { }} is called in the words of the trust block, by the condition's text. */
    public Map<String, String> getTrustLabels() { return trustLabels; }
    /** The measures the ladder written from {@code trust { }} uses; they belong to this decision and are not part of the script's. */
    public List<MeasureDef> getTrustMeasures() { return trustMeasures; }
    /** The highest rung of the bound ladder this decision may reach ({@code ceiling: suggest}); null for the first. */
    public String getCeilingRung() { return ceilingRung; }
    public int getCeilingRungLine() { return ceilingRungLine; }
    public void setCeilingRung(String rung, int line) { this.ceilingRung = rung; this.ceilingRungLine = line; }
    /** The artifact this decision proposes ({@code proposes: script}) instead of {@code choices}, or null. */
    public String getProposes() { return proposes; }
    public int getProposesLine() { return proposesLine; }
    public void setProposes(String proposes, int line) { this.proposes = proposes; this.proposesLine = line; }
}
