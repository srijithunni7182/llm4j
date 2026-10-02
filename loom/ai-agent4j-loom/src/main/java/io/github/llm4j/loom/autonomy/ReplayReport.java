package io.github.llm4j.loom.autonomy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.ast.DecisionDef;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a replay found, from one model object: Markdown for people, JSON for scripts. Sections in a fixed order: what was replayed and what was
 * not, the tools, the headline table, the level the candidate would earn, the flips (unsafe first), the scopes, stability.
 */
public final class ReplayReport {

    /** One replayed case. {@code choices} holds the candidate's choice for each repeat. */
    public record Row(String caseId, String scope, String humanVerdict, String incumbent, boolean incumbentMalformed, List<String> choices, String reasoning,
                      Map<String, String> fields, boolean afterSimulation, boolean nonDeterministic, long tokens, BigDecimal cost) {
        public String candidate() {
            return choices.get(0);
        }

        public boolean stable() {
            return choices.stream().distinct().count() == 1;
        }
    }

    /** One case that could not be replayed, and why. */
    public record Skip(String caseId, String scope, String reason, String detail) { }

    public record Side(int cases, double rate, double lowerBound, double dangerousRate, double coverage) { }

    public record Flip(String caseId, String scope, String incumbent, String candidate, String human, boolean unsafe, String reasoning, Map<String, String> fields) { }

    public String decision;
    public String id;
    public String candidateIdentity;
    public String incumbentIdentity;
    public boolean partial;
    public String stoppedBecause;
    public int selected;
    public int replayed;
    public Map<String, Integer> skipped = new TreeMap<>();
    public double replayableShare;
    public List<String> toolsAllowed = new ArrayList<>();
    public List<String> toolsSimulated = new ArrayList<>();
    public List<String> toolsLive = new ArrayList<>();
    public int afterSimulationCount;
    public Side incumbent;
    public Side candidate;
    public String levelEarned;
    public String levelEarnedWhy;
    public List<Flip> flips = new ArrayList<>();
    public Map<String, Map<String, Side>> scopes = new TreeMap<>();
    public int repeat = 1;
    public double stability = 1;
    public long tokens;
    public BigDecimal cost = BigDecimal.ZERO;
    public double projectedCostPerCase;
    public Map<String, String> policyHashes = new LinkedHashMap<>();
    public String note;
    public List<Skip> skips = new ArrayList<>();

    private static Side side(List<AgreementStats.Sample> samples, List<DecisionDef.Mistake> dangerous) {
        AgreementStats.Figures f = AgreementStats.of(samples, dangerous);
        return new Side(f.cases(), f.rate(), f.lowerBound(), f.dangerousRate(), f.coverage());
    }

    /** Builds the report from what was replayed, against the decision's own rules. */
    public static ReplayReport build(DecisionDef def, String id, List<Row> rows, List<Skip> skips, int selected) {
        ReplayReport r = new ReplayReport();
        r.decision = def.getName();
        r.id = id;
        r.selected = selected;
        r.replayed = rows.size();
        r.skips = new ArrayList<>(skips);
        for (Skip s : skips) r.skipped.merge(s.reason(), 1, Integer::sum);
        r.replayableShare = selected == 0 ? 0 : (double) rows.size() / selected;

        List<AgreementStats.Sample> mine = new ArrayList<>(), theirs = new ArrayList<>();
        Map<String, List<AgreementStats.Sample>> mineByScope = new TreeMap<>(), theirsByScope = new TreeMap<>();
        long stable = 0;
        for (Row row : rows) {
            AgreementStats.Sample inc = new AgreementStats.Sample(row.incumbent(), row.humanVerdict(), row.incumbentMalformed(), false);
            AgreementStats.Sample cand = new AgreementStats.Sample(row.candidate(), row.humanVerdict());
            mine.add(inc);
            theirs.add(cand);
            mineByScope.computeIfAbsent(row.scope(), k -> new ArrayList<>()).add(inc);
            theirsByScope.computeIfAbsent(row.scope(), k -> new ArrayList<>()).add(cand);
            r.tokens += row.tokens();
            r.cost = r.cost.add(row.cost());
            if (row.stable()) stable++;
            if (row.afterSimulation()) r.afterSimulationCount++;
            if (!row.candidate().equals(row.incumbent())) {
                boolean unsafe = def.getDangerous().stream().anyMatch(m -> m.proposed().equals(row.candidate()) && m.decided().equals(row.humanVerdict()));
                r.flips.add(new Flip(row.caseId(), row.scope(), row.incumbent(), row.candidate(), row.humanVerdict(), unsafe, row.reasoning(), row.fields()));
            }
        }
        r.flips.sort((a, b) -> a.unsafe() == b.unsafe() ? a.caseId().compareTo(b.caseId()) : (a.unsafe() ? -1 : 1));
        r.incumbent = side(mine, def.getDangerous());
        r.candidate = side(theirs, def.getDangerous());
        for (String scope : mineByScope.keySet()) {
            Map<String, Side> m = new LinkedHashMap<>();
            m.put("incumbent", side(mineByScope.get(scope), def.getDangerous()));
            m.put("candidate", side(theirsByScope.get(scope), def.getDangerous()));
            r.scopes.put(scope, m);
        }
        r.stability = rows.isEmpty() ? 1 : (double) stable / rows.size();
        r.projectedCostPerCase = rows.isEmpty() ? 0 : r.cost.doubleValue() / rows.size();
        earned(def, rows, theirs, r);
        return r;
    }

    /** The highest level whose rule the candidate's replay would satisfy, judged on the replayed cases as though they were its blind cases. */
    private static void earned(DecisionDef def, List<Row> rows, List<AgreementStats.Sample> candidate, ReplayReport r) {
        AgreementStats.Figures f = AgreementStats.of(candidate, def.getDangerous());
        Level earned = Level.WATCH;
        String why = "no rule is met";
        for (Level l : List.of(Level.SUGGEST, Level.ACT)) {
            DecisionDef.UpRule rule = def.getUpRules().get(l);
            if (rule == null) { why = "the script has no rule for " + l.word(); break; }
            boolean met = f.cases() >= rule.cases() && f.lowerBound() * 100 >= rule.agreeingAtLeast()
                    && (!rule.noDangerous() || f.dangerous() == 0) && (rule.dangerousAtMost() == null || f.dangerousRate() * 100 <= rule.dangerousAtMost());
            if (!met) {
                why = String.format("%s needs %d cases at a lower bound of %.0f%%; the replay has %d cases at %.1f%%", l.word(), rule.cases(), rule.agreeingAtLeast(), f.cases(), f.lowerBound() * 100);
                break;
            }
            earned = l;
            why = "every rule up to " + l.word() + " is met by " + f.cases() + " replayed cases";
        }
        r.levelEarned = earned.word();
        r.levelEarnedWhy = why + (earned.compareTo(def.getCeiling()) > 0 ? " (the script's ceiling, never go above " + def.getCeiling().word() + ", still applies)" : "");
    }

    // ---- output ---------------------------------------------------------------------------------------------------------------

    public String json() {
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }

    private static String side(String label, Side s) {
        return String.format("| %s | %d | %s | %s | %s | %s |%n", label, s.cases(), pct(s.rate()), pct(s.lowerBound()), pct(s.dangerousRate()), pct(s.coverage()));
    }

    private static String line(String text) {
        return io.github.llm4j.loom.travel.RunTravel.neutralise(text).replace("|", "\\|");
    }

    public String markdown() {
        StringBuilder b = new StringBuilder();
        b.append("# Replay of ").append(decision).append(" (").append(id).append(")\n\n");
        if (partial) b.append("**Partial:** ").append(stoppedBecause).append("\n\n");
        b.append("## What was replayed\n\n");
        b.append(String.format("%d cases selected, %d replayed (%s of the selected cases).%n%n", selected, replayed, pct(replayableShare)));
        if (!skipped.isEmpty()) {
            b.append("Not replayable:\n\n");
            skipped.forEach((k, v) -> b.append("- ").append(k).append(": ").append(v).append('\n'));
            b.append('\n');
        }
        b.append("## Tools in replay\n\n");
        b.append("- allowed to run (replay: allow): ").append(toolsAllowed.isEmpty() ? "none" : String.join(", ", toolsAllowed)).append('\n');
        b.append("- simulated (not performed): ").append(toolsSimulated.isEmpty() ? "none" : String.join(", ", toolsSimulated)).append('\n');
        b.append("- read live (not recorded; non-deterministic): ").append(toolsLive.isEmpty() ? "none" : String.join(", ", toolsLive)).append('\n');
        if (afterSimulationCount > 0) b.append("- ").append(afterSimulationCount).append(" proposals were made after a simulated call, so on less information than the original\n");
        b.append("\n## Headline\n\n| | cases | agreement | lower bound | dangerous | coverage |\n|---|---|---|---|---|---|\n");
        b.append(side("incumbent", incumbent)).append(side("candidate", candidate));
        b.append(String.format("%nCost of this replay: %d tokens, %s; about %.4f per case.%n", tokens, cost.toPlainString(), projectedCostPerCase));
        b.append("\n## The level the candidate would earn\n\n").append(levelEarned).append(": ").append(levelEarnedWhy).append("\n\n");
        b.append("## Flips (").append(flips.size()).append(")\n\n");
        if (flips.isEmpty()) b.append("None: the candidate chose as the incumbent did on every replayed case.\n");
        for (Flip f : flips) {
            b.append("- ").append(f.unsafe() ? "**UNSAFE** " : "").append(f.caseId()).append(" [").append(line(f.scope())).append("]: incumbent ").append(f.incumbent())
                    .append(", candidate ").append(f.candidate()).append(", person decided ").append(f.human()).append('\n');
            b.append("    - values: ").append(line(String.valueOf(f.fields()))).append('\n');
            b.append("    - candidate's reasoning: ").append(line(f.reasoning() == null ? "" : f.reasoning())).append('\n');
        }
        b.append("\n## By scope\n\n");
        for (Map.Entry<String, Map<String, Side>> e : scopes.entrySet()) {
            b.append("### ").append(line(e.getKey())).append("\n\n| | cases | agreement | lower bound | dangerous | coverage |\n|---|---|---|---|---|---|\n");
            b.append(side("incumbent", e.getValue().get("incumbent"))).append(side("candidate", e.getValue().get("candidate"))).append('\n');
        }
        if (repeat > 1) b.append("## Stability\n\nEach case was replayed ").append(repeat).append(" times; ").append(pct(stability)).append(" of cases got the same choice every time.\n\n");
        if (!policyHashes.isEmpty()) {
            b.append("## Policy replaced\n\n");
            policyHashes.forEach((k, v) -> b.append("- ").append(k).append(": ").append(v).append('\n'));
        }
        if (note != null) b.append('\n').append(note).append('\n');
        return b.toString();
    }
}
