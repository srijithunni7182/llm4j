package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.autonomy.AgentIdentity;
import io.github.llm4j.loom.autonomy.Ledger;
import io.github.llm4j.loom.autonomy.Rec;
import io.github.llm4j.loom.autonomy.expr.V;
import io.github.llm4j.loom.ladder.LCase;
import io.github.llm4j.loom.ladder.LadderEngine;
import io.github.llm4j.loom.ladder.LadderPolicy;
import io.github.llm4j.loom.ladder.LevelLog;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The operator's commands for a decision that binds a ladder: where each scope stands and why, and the ways a person moves it. */
final class LadderCommands {

    private LadderCommands() { }

    private static Ledger ledger(Path store) {
        return AutonomySupport.ledger(store);
    }

    // ---- status ----------------------------------------------------------------------------------------------------------

    static Map<String, Object> describe(DecisionDef def, LoomScript script, Ledger ledger, Clock clock, StringBuilder text) {
        LadderPolicy policy = LadderPolicy.of(script, def);
        LadderEngine engine = new LadderEngine(policy, ledger, clock);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("decision", def.getName());
        d.put("ladder", policy.ladderName());
        boolean frozen = engine.frozen();
        d.put("frozen", frozen);
        text.append(def.getName()).append("   ladder ").append(policy.ladderName()).append(", may climb as far as ").append(policy.nameOf(policy.ceiling()))
                .append(frozen ? "   FROZEN (the autonomous rungs are stopped)" : "").append('\n');
        int torn = ledger.unreadable(def.getName());
        if (torn > 0) {
            d.put("unreadableLedgerLines", torn);
            text.append("  WARNING: ").append(torn).append(" ledger line(s) could not be read (a write that was cut off); the next append repairs it.\n");
        }
        List<LCase> cases = engine.cases();
        List<Map<String, Object>> scopes = new ArrayList<>();
        long now = clock.millis();
        for (Map.Entry<String, LevelLog.Standing> e : engine.log().all().entrySet()) {
            String scope = e.getKey();
            LevelLog.Standing st = e.getValue();
            int effective = engine.effective(st);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("scope", scope);
            s.put("rung", st.rungName());
            s.put("effectiveRung", policy.nameOf(effective));
            s.put("epoch", st.epoch());
            s.put("agent", AgentIdentity.shorten(st.identity()));
            s.put("servedModel", st.servedModel() == null ? "unreported" : st.servedModel());
            s.put("forced", st.forced());
            V.Cs history = engine.history(scope, st.epoch());
            s.put("cases", history.cases().size());
            text.append("  scope ").append(scope).append("   rung ").append(st.rungName());
            if (effective != st.rung()) text.append(" (running at ").append(policy.nameOf(effective)).append(")");
            if (st.forced()) text.append("   FORCED: set by hand, not supported by the record");
            text.append("   (epoch ").append(st.epoch()).append(", agent ").append(AgentIdentity.shorten(st.identity())).append(", served model ")
                    .append(st.servedModel() == null ? "unreported" : st.servedModel()).append(")\n");
            text.append("    ").append(history.cases().size()).append(" answered cases in this epoch\n");
            if (def.getChoices().contains("escalate") && !history.cases().isEmpty()) {
                long escalated = history.cases().stream().filter(c -> c.get("proposal") instanceof V.Rec p && p.get("choice") instanceof V.Str ch && ch.value().equals("escalate")).count();
                double coverage = 1.0 - (double) escalated / history.cases().size();
                text.append("    coverage ").append(String.format("%.1f%%", coverage * 100)).append(": the share of proposals that were not escalate (an agent that always escalates agrees only when people do)\n");
                s.put("coverage", coverage);
            }

            int next = st.rung() + 1;
            if (next >= policy.rungs().size() || next > policy.ceiling()) {
                text.append("    no further climb: ").append(next >= policy.rungs().size() ? "this is the top rung" : "the ceiling is " + policy.nameOf(policy.ceiling())).append('\n');
            } else {
                List<LadderPolicy.Term> terms = policy.explainClimb(next, history, now);
                boolean met = terms.stream().allMatch(t -> t.met().orElse(false));
                text.append("    towards ").append(policy.nameOf(next)).append(met ? ": every condition is met" : ":").append('\n');
                if (!met) for (LadderPolicy.Term t : terms) text.append("      - ").append(term(t)).append('\n');
                s.put("towards", policy.nameOf(next));
                s.put("climb", terms(terms));
            }
            List<LadderPolicy.Term> slips = policy.explainSlip(st.rung(), history, now);
            if (!slips.isEmpty()) {
                text.append("    slips to ").append(policy.slip(st.rung(), history, now).map(policy::nameOf).orElse("a lower rung")).append(" when any of:\n");
                for (LadderPolicy.Term t : slips) text.append("      - ").append(term(t)).append('\n');
                s.put("slip", terms(slips));
            }
            engine.openProposal(scope).ifPresent(p -> {
                text.append("    open promotion proposal: to ").append(p.to()).append(" on ").append(p.cases()).append(" cases, waiting for ").append(policy.approver()).append('\n');
                s.put("openProposal", p.to());
            });
            Instant staleBefore = clock.instant().minus(Duration.ofDays(def.getStaleDays()));
            List<String> stale = cases.stream().filter(c -> !c.superseded() && c.scope().equals(scope) && !c.responded() && c.at().isBefore(staleBefore)).map(LCase::id).toList();
            if (!stale.isEmpty()) {
                text.append("    stale: ").append(stale.size()).append(" case(s) with no answer after ").append(def.getStaleDays()).append(" days: ")
                        .append(String.join(", ", stale.subList(0, Math.min(5, stale.size())))).append('\n');
                s.put("stale", stale);
            }
            Optional<Rec> last = ledger.recordsOfKind(def.getName(), Rec.LEVEL).stream().filter(r -> scope.equals(r.str("scope"))).reduce((a, b) -> b);
            last.ifPresent(r -> {
                text.append("    last change: ").append(r.at()).append(' ').append(r.str("from") == null ? "(start)" : r.str("from")).append(" -> ").append(r.str("to")).append(" (")
                        .append(r.str("kind")).append(r.flag("forced") ? ", forced" : "").append(", by ").append(r.str("by")).append("): ").append(r.str("reason")).append('\n');
                s.put("lastChange", Map.of("time", r.at().toString(), "to", String.valueOf(r.str("to")), "kind", String.valueOf(r.str("kind")), "reason", String.valueOf(r.str("reason"))));
            });
            scopes.add(s);
        }
        d.put("scopes", scopes);
        if (scopes.isEmpty()) text.append("  no cases yet\n");
        text.append('\n');
        return d;
    }

    private static String term(LadderPolicy.Term t) {
        String verdict = t.met().map(m -> m ? "met" : "not met").orElse("unknown");
        return t.text() + "   [" + t.actual() + (t.needed().isEmpty() ? "" : " vs " + t.needed()) + "]   " + verdict;
    }

    private static List<Map<String, Object>> terms(List<LadderPolicy.Term> terms) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LadderPolicy.Term t : terms) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("term", t.text());
            m.put("actual", t.actual());
            m.put("needed", t.needed());
            m.put("met", t.met().orElse(null));
            out.add(m);
        }
        return out;
    }

    // ---- moving ----------------------------------------------------------------------------------------------------------

    static int move(Path store, DecisionDef def, LoomScript script, String scope, String to, boolean up, boolean force, String reason, String by, WeaveEnv env) {
        LadderPolicy policy = LadderPolicy.of(script, def);
        LadderEngine engine = new LadderEngine(policy, ledger(store), env.clock());
        Optional<LevelLog.Standing> st = engine.log().current(scope);
        if (st.isEmpty()) {
            env.err().println("Error: no such scope: \"" + scope + "\" (scopes seen: " + String.join(", ", engine.log().all().keySet()) + ")");
            return 2;
        }
        String target = to;
        if (target == null) {
            int next = st.get().rung() + (up ? 1 : -1);
            if (next < 0 || next >= policy.rungs().size()) {
                env.err().println("Error: " + scope + " is already on " + st.get().rungName() + ".");
                return 2;
            }
            target = policy.nameOf(next);
        } else if (policy.indexOf(target) < 0) {
            env.err().println("Error: --to takes one of the rungs: " + String.join(", ", policy.rungs()) + ".");
            return 2;
        } else if (up != (policy.indexOf(target) > st.get().rung()) && policy.indexOf(target) != st.get().rung()) {
            env.err().println("Error: " + (up ? "promote" : "demote") + " moves " + (up ? "up" : "down") + ", and " + target + " is " + (up ? "below" : "above") + " " + st.get().rungName() + ".");
            return 2;
        }
        try {
            LadderEngine.Change change = engine.set(scope, target, force, reason, by != null ? by : AutonomyCommands.operator());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("decision", def.getName());
            data.put("scope", scope);
            data.put("from", change.from());
            data.put("to", change.to());
            data.put("rule", "by hand");
            data.put("reason", reason);
            if (change.forced()) data.put("forced", true);
            AutonomyCommands.audit(store, "level_changed", data);
            env.out().println("Moved " + scope + " from " + change.from() + " to " + change.to() + (change.forced() ? " (FORCED: shown as forced until the record supports it)" : "") + ".");
            return 0;
        } catch (IllegalStateException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
    }

    static int decide(Path store, DecisionDef def, LoomScript script, String scope, boolean approve, String reason, String by, WeaveEnv env) {
        LadderEngine engine = new LadderEngine(LadderPolicy.of(script, def), ledger(store), env.clock());
        try {
            LadderEngine.Change change = approve ? engine.approve(scope, by, reason) : engine.reject(scope, by, reason);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("decision", def.getName());
            data.put("scope", scope);
            data.put("rule", approve ? "promotion approved" : "promotion rejected");
            data.put("to", change.to());
            data.put("reason", reason);
            AutonomyCommands.audit(store, approve ? "promotion_approved" : "promotion_rejected", data);
            env.out().println(approve ? "Approved: " + scope + " moved to " + change.to() + "." : "Rejected the promotion of " + scope + " to " + change.to() + ".");
            return 0;
        } catch (IllegalStateException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
    }

    static int outcome(Path store, DecisionDef def, LoomScript script, String caseId, String result, String value, String note, WeaveEnv env) {
        LadderEngine engine = new LadderEngine(LadderPolicy.of(script, def), ledger(store), env.clock());
        try {
            Object typed = value == null ? null : value.matches("-?\\d+(\\.\\d+)?") ? (Object) Double.valueOf(value) : value;
            List<LadderEngine.Change> changes = engine.outcome(caseId, result, typed, note);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("decision", def.getName());
            data.put("case", caseId);
            data.put("result", result);
            if (value != null) data.put("value", value);
            if (note != null) data.put("note", note);
            data.put("changes", changes.stream().map(LadderEngine.Change::sentence).toList());
            AutonomyCommands.audit(store, "outcome_recorded", data);
            env.out().println("Recorded: " + caseId + " " + result + (value == null ? "" : " = " + value) + ".");
            for (LadderEngine.Change c : changes) env.out().println("  " + c.sentence());
            return 0;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
    }
}
