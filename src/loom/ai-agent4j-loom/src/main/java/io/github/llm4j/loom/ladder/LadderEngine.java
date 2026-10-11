package io.github.llm4j.loom.ladder;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.RungDef;
import io.github.llm4j.loom.autonomy.Ledger;
import io.github.llm4j.loom.autonomy.Rec;
import io.github.llm4j.loom.autonomy.expr.V;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * Everything that changes where a scope stands on its ladder: a first case, a new epoch, a climb or a slip, a promotion approved, a hand-set rung, a
 * freeze, an outcome, a change of rules. The executor and the {@code weave} commands both go through here, so a change is made, recorded and explained
 * the same way whoever asked for it.
 */
public final class LadderEngine {

    /** One change worth telling someone about. {@code kind}: promoted, demoted, proposed, rejected, epoch, set. */
    public record Change(String kind, String scope, String from, String to, String reason, String by, boolean forced) {
        public String sentence() {
            String where = scope.isEmpty() || scope.equals("all") ? "the decision" : "scope " + scope;
            switch (kind) {
                case "proposed": return "promotion of " + where + " to " + to + " proposed: " + reason;
                case "rejected": return "promotion of " + where + " to " + to + " rejected: " + reason;
                default: return where + " moved from " + from + " to " + to + " (" + kind + (forced ? ", forced" : "") + "): " + reason;
            }
        }
    }

    /** How one case is run: the rung it is on, what the runtime enforces, and why. */
    public record Plan(String rung, RungDef.Mode mode, String how) { }

    /** A promotion waiting for the named person. */
    public record Proposal(String id, String scope, String to, int cases, String why, String approver) { }

    private static final int RETRIES = 5;

    private final LadderPolicy policy;
    private final Ledger ledger;
    private final Clock clock;
    private final LevelLog log;
    private ToIntFunction<LevelLog.Standing> inherit;

    public LadderEngine(LadderPolicy policy, Ledger ledger, Clock clock) {
        this.policy = policy;
        this.ledger = ledger;
        this.clock = clock;
        this.log = new LevelLog(policy.decisionName(), policy.rungs(), ledger);
    }

    /** What a new epoch inherits when the decision says "test it on past cases": given the old standing, the rung the new agent has earned. */
    public void inheritWith(ToIntFunction<LevelLog.Standing> inherit) {
        this.inherit = inherit;
    }

    public LadderPolicy policy() { return policy; }

    public LevelLog log() { return log; }

    private String decision() { return policy.decisionName(); }

    private Instant now() { return clock.instant(); }

    // ---- reading -------------------------------------------------------------------------------------------------------

    public List<LCase> cases() {
        return LedgerFold.cases(ledger.records(decision()));
    }

    /** The cases of this scope and epoch that count. */
    public V.Cs history(String scope, int epoch) {
        return HistoryBuilder.history(cases(), scope, epoch, policy.decision().getRememberTypes());
    }

    /** The cases of this scope in every epoch. */
    public V.Cs historyAll(String scope) {
        return HistoryBuilder.history(cases(), scope, -1, policy.decision().getRememberTypes());
    }

    private V.Cs history(String scope, LevelLog.Standing s) {
        return history(scope, s.epoch());
    }

    /**
     * The scope's standing, created on the first case. A change of agent (or of the model the provider says it served) starts a new epoch first:
     * evidence earned by one is never credited to the other.
     */
    public LevelLog.Standing standing(String scope, String identity, String servedModel) {
        for (int i = 0; i < RETRIES; i++) {
            Optional<LevelLog.Standing> found = log.current(scope);
            if (found.isEmpty()) {
                LevelLog.Standing first = make(0, 1, 1, identity, servedModel, false, "first case of this scope");
                if (log.write(scope, 0, first, "first", null, "runtime", now(), policy.rulesText())) return first;
                continue;
            }
            LevelLog.Standing cur = found.get();
            String why = null;
            if (!cur.identity().equals(identity)) {
                why = "the agent behind " + decision() + " changed (" + shorten(cur.identity()) + " to " + shorten(identity) + ")";
            } else if (known(cur.servedModel()) && known(servedModel) && !cur.servedModel().equals(servedModel)) {
                why = "the served model changed from " + cur.servedModel() + " to " + servedModel + " with the script unchanged";
            }
            if (why == null && !known(cur.servedModel()) && known(servedModel)) {
                // the first time the provider names the model: remember it, so a later change behind the same script can be seen
                LevelLog.Standing named = new LevelLog.Standing(cur.rung(), cur.rungName(), cur.version() + 1, cur.epoch(), cur.identity(), servedModel, cur.forced(), now(),
                        cur.reason(), cur.rules());
                if (log.write(scope, cur.version(), named, "model", cur.rungName(), "runtime", now(), policy.rulesText())) return named;
                continue;
            }
            if (why == null) return cur;
            int rung;
            String how;
            switch (policy.decision().getOnChange()) {
                case KEEP_TRUST:
                    rung = cur.rung();
                    how = "keep the trust";
                    break;
                case TEST_ON_PAST:
                    rung = inherit == null ? 0 : Math.min(cur.rung(), Math.max(0, inherit.applyAsInt(cur)));
                    how = inherit == null ? "start over (no replay available)" : "test it on past cases";
                    break;
                default:
                    rung = 0;
                    how = "start over";
            }
            LevelLog.Standing next = make(rung, cur.version() + 1, cur.epoch() + 1, identity, known(servedModel) ? servedModel : cur.servedModel(), false, why + ": " + how);
            if (log.write(scope, cur.version(), next, "epoch", cur.rungName(), "runtime", now(), policy.rulesText())) return next;
        }
        return log.current(scope).orElseThrow(() -> new IllegalStateException("cannot create the standing of scope " + scope));
    }

    private static boolean known(String model) {
        return model != null && !model.isBlank() && !model.equals("unreported");
    }

    private static String shorten(String s) {
        return s == null ? "?" : s.length() <= 8 ? s : s.substring(0, 8);
    }

    private LevelLog.Standing make(int rung, int version, int epoch, String identity, String servedModel, boolean forced, String reason) {
        return new LevelLog.Standing(rung, policy.nameOf(rung), version, epoch, identity, servedModel, forced, now(), reason, policy.fingerprint());
    }

    /** The rung a case runs at: the earned rung, held at the ceiling, and below the first autonomous rung while frozen. */
    public int effective(LevelLog.Standing s) {
        int rung = Math.min(s.rung(), policy.ceiling());
        if (log.frozen()) {
            int fa = policy.firstAutonomous();
            if (fa >= 0 && rung >= fa) rung = Math.max(0, fa - 1);
        }
        return rung;
    }

    /** How this case is run: its rung's mode, except that a sampled case goes to a person blind and an escalated one goes to a person. */
    public Plan plan(String scope, String caseId, LevelLog.Standing s, V.Rec current, V.Cs history, long nowMillis) {
        int rung = effective(s);
        RungDef.Mode mode = policy.modeOf(rung);
        String how = rung < s.rung() ? (log.frozen() && rung < Math.min(s.rung(), policy.ceiling()) ? "frozen" : "ceiling") : policy.nameOf(rung);
        if (mode == RungDef.Mode.AUTONOMOUS) {
            if (policy.escalates(rung, current, history, nowMillis)) return new Plan(policy.nameOf(rung), RungDef.Mode.CONFIRM, "escalate");
            if (policy.sampled(rung, caseId)) return new Plan(policy.nameOf(rung), RungDef.Mode.BLIND, "sample");
        }
        return new Plan(policy.nameOf(rung), mode, how);
    }

    // ---- moving --------------------------------------------------------------------------------------------------------

    private static String because(List<LadderPolicy.Term> terms, boolean wantMet) {
        return terms.stream().filter(t -> t.met().map(m -> m == wantMet).orElse(false))
                .map(t -> t.text() + (t.needed().isEmpty() ? " [" + t.actual() + "]" : " [" + t.actual() + " vs " + t.needed() + "]"))
                .collect(Collectors.joining("; "));
    }

    /** Applies a slip that holds, else makes or proposes a climb that has been earned; at most one step. The changes made. */
    public List<Change> afterResponse(String scope) {
        List<Change> out = new ArrayList<>();
        for (int i = 0; i < RETRIES; i++) {
            Optional<LevelLog.Standing> found = log.current(scope);
            if (found.isEmpty()) return out;
            LevelLog.Standing cur = found.get();
            V.Cs history = history(scope, cur);
            long t = now().toEpochMilli();

            Optional<Integer> slip = policy.slip(cur.rung(), history, t);
            if (slip.isPresent()) {
                String why = because(policy.explainSlip(cur.rung(), history, t), true);
                LevelLog.Standing next = withRung(cur, slip.get(), "slip: " + why, false);
                if (log.write(scope, cur.version(), next, "demoted", cur.rungName(), "runtime", now(), policy.rulesText())) {
                    out.add(new Change("demoted", scope, cur.rungName(), next.rungName(), next.reason(), "runtime", false));
                    return out;
                }
                continue;
            }
            int to = cur.rung() + 1;
            if (to > policy.ceiling() || to >= policy.rungs().size() || !policy.climbs(to, history, t)) return out;
            if (policy.slip(to, history, t).isPresent()) return out;                      // it would only flap
            String why = because(policy.explainClimb(to, history, t), true);
            if (policy.automatic()) {
                LevelLog.Standing next = withRung(cur, to, "climb: " + why, false);
                if (log.write(scope, cur.version(), next, "promoted", cur.rungName(), "runtime", now(), policy.rulesText())) {
                    out.add(new Change("promoted", scope, cur.rungName(), next.rungName(), next.reason(), "runtime", false));
                    return out;
                }
                continue;
            }
            Optional<Change> proposed = propose(scope, cur, to, history.cases().size(), why);
            proposed.ifPresent(out::add);
            return out;
        }
        return out;
    }

    private LevelLog.Standing withRung(LevelLog.Standing cur, int rung, String reason, boolean forced) {
        return new LevelLog.Standing(rung, policy.nameOf(rung), cur.version() + 1, cur.epoch(), cur.identity(), cur.servedModel(), forced, now(), reason, policy.fingerprint());
    }

    private Optional<Change> propose(String scope, LevelLog.Standing cur, int to, int cases, String why) {
        String target = policy.nameOf(to);
        if (openProposal(scope).filter(p -> p.to().equals(target)).isPresent()) return Optional.empty();
        for (Rec r : ledger.recordsOfKind(decision(), Rec.PROMOTION_DECIDED)) {
            if (!"rejected".equals(r.str("result")) || !r.str("proposal").contains("#promotion#" + scope + "#" + target + "#" + cur.epoch() + "#")) continue;
            int then = r.num("cases") == null ? 0 : r.num("cases").intValue();
            if (cases < Math.ceil(then * 1.2)) return Optional.empty();                    // not asked again until the evidence has grown by a fifth
        }
        String id = decision() + "#promotion#" + scope + "#" + target + "#" + cur.epoch() + "#" + cases;
        ledger.append(new Rec(id, decision(), Rec.PROMOTION_PROPOSED, now(), "", 1,
                Rec.map("scope", scope, "to", target, "cases", cases, "why", why, "approver", policy.approver(), "epoch", cur.epoch())));
        return Optional.of(new Change("proposed", scope, cur.rungName(), target, why + "; waiting for " + policy.approver(), "runtime", false));
    }

    /** The open promotion proposal of the scope, if any: proposed, not answered, and still above where the scope stands. */
    public Optional<Proposal> openProposal(String scope) {
        Optional<LevelLog.Standing> cur = log.current(scope);
        if (cur.isEmpty()) return Optional.empty();
        List<String> answered = ledger.recordsOfKind(decision(), Rec.PROMOTION_DECIDED).stream().map(r -> r.str("proposal")).toList();
        Proposal open = null;
        for (Rec r : ledger.recordsOfKind(decision(), Rec.PROMOTION_PROPOSED)) {
            if (!scope.equals(r.str("scope")) || answered.contains(r.id())) continue;
            int to = policy.indexOf(r.str("to"));
            int epoch = r.num("epoch") == null ? 1 : r.num("epoch").intValue();
            if (to <= cur.get().rung() || epoch != cur.get().epoch()) continue;
            open = new Proposal(r.id(), scope, r.str("to"), r.num("cases") == null ? 0 : r.num("cases").intValue(), r.str("why"), r.str("approver"));
        }
        return Optional.ofNullable(open);
    }

    /** Approves the open proposal of the scope and moves it up. */
    public Change approve(String scope, String by, String reason) {
        Proposal p = openProposal(scope).orElseThrow(() -> new IllegalStateException("there is no open promotion proposal for " + shown(scope)));
        if (policy.approver() != null && !policy.approver().equals(by)) {
            throw new IllegalStateException("only " + policy.approver() + " may approve moving up (you said " + by + ")");
        }
        for (int i = 0; i < RETRIES; i++) {
            LevelLog.Standing cur = log.current(scope).orElseThrow(() -> new IllegalStateException("no such scope: " + scope));
            LevelLog.Standing next = withRung(cur, policy.indexOf(p.to()), "approved by " + by + ": " + reason, false);
            if (log.write(scope, cur.version(), next, "promoted", cur.rungName(), by, now(), policy.rulesText())) {
                ledger.append(new Rec(p.id() + "#approved", decision(), Rec.PROMOTION_DECIDED, now(), "", 1, Rec.map("proposal", p.id(), "result", "approved", "by", by, "reason", reason)));
                return new Change("promoted", scope, cur.rungName(), next.rungName(), next.reason(), by, false);
            }
        }
        throw new IllegalStateException("the level changed while you were approving; look again");
    }

    /** Rejects the open proposal; it is not asked again until the evidence has grown by a fifth. */
    public Change reject(String scope, String by, String reason) {
        Proposal p = openProposal(scope).orElseThrow(() -> new IllegalStateException("there is no open promotion proposal for " + shown(scope)));
        if (policy.approver() != null && !policy.approver().equals(by)) {
            throw new IllegalStateException("only " + policy.approver() + " may decide on moving up (you said " + by + ")");
        }
        ledger.append(new Rec(p.id() + "#rejected", decision(), Rec.PROMOTION_DECIDED, now(), "", 1,
                Rec.map("proposal", p.id(), "result", "rejected", "by", by, "reason", reason, "cases", p.cases())));
        LevelLog.Standing cur = log.current(scope).orElseThrow();
        return new Change("rejected", scope, cur.rungName(), p.to(), reason, by, false);
    }

    private static String shown(String scope) {
        return scope.isEmpty() || scope.equals("all") ? "this decision" : "scope " + scope;
    }

    /**
     * Sets a rung by hand. Going up beyond the ceiling or beyond what the record supports needs {@code force} (and is then marked forced); coming
     * down never does.
     */
    public Change set(String scope, String rungName, boolean force, String reason, String by) {
        int to = policy.indexOf(rungName);
        if (to < 0) throw new IllegalStateException("no rung named " + rungName + " in ladder " + policy.ladderName() + " (rungs: " + String.join(", ", policy.rungs()) + ")");
        for (int i = 0; i < RETRIES; i++) {
            LevelLog.Standing cur = log.current(scope).orElseThrow(() -> new IllegalStateException("no such scope: \"" + scope + "\" (scopes seen: "
                    + String.join(", ", log.all().keySet()) + ")"));
            boolean forced = false;
            if (to > cur.rung()) {
                boolean beyond = to > policy.ceiling();
                V.Cs history = history(scope, cur);
                boolean unsupported = false;
                for (int r = cur.rung() + 1; r <= to; r++) if (!policy.climbs(r, history, now().toEpochMilli())) unsupported = true;
                if ((beyond || unsupported) && !force) {
                    throw new IllegalStateException("moving " + scope + " up to " + rungName + " is " + (beyond ? "above the ceiling (" + policy.nameOf(policy.ceiling()) + ")" : "not supported by the evidence")
                            + "; add --force to do it anyway (it is then shown as forced)");
                }
                forced = beyond || unsupported;
            }
            LevelLog.Standing next = withRung(cur, to, reason, forced);
            if (log.write(scope, cur.version(), next, "set", cur.rungName(), by, now(), policy.rulesText())) {
                return new Change("set", scope, cur.rungName(), next.rungName(), reason, by, forced);
            }
        }
        throw new IllegalStateException("the level changed while you were changing it; look again");
    }

    public boolean frozen() {
        return log.frozen();
    }

    public void freeze(String reason, String by) {
        log.setFrozen(true, reason, by, now());
    }

    public void unfreeze(String reason, String by) {
        log.setFrozen(false, reason, by, now());
    }

    /** Records a later fact about a case; it may cause a slip. */
    public List<Change> outcome(String caseId, String name, Object value, String note) {
        LCase c = cases().stream().filter(x -> x.id().equals(caseId) && !x.superseded()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("there is no case " + caseId + " in decision " + decision()));
        String digest = Integer.toHexString((String.valueOf(value) + "|" + note).hashCode());
        ledger.append(new Rec(caseId + "#outcome#" + name + "#" + digest, decision(), Rec.OUTCOME, now(), caseId, c.generation(),
                Rec.map("result", name, "value", value, "note", note)));
        return afterResponse(c.scope());
    }

    // ---- change of rules -----------------------------------------------------------------------------------------------

    /**
     * Checks every scope against the rules as the script now stands. A scope that no longer meets what put it where it is (a tightened condition, a
     * lowered ceiling) is demoted at once; the new rules are recorded. Nothing is promoted by a change of rules: a scope that now qualifies for more
     * climbs with its next case, the usual way.
     */
    public List<Change> reevaluate() {
        List<Change> out = new ArrayList<>();
        String fingerprint = policy.fingerprint();
        for (String scope : new ArrayList<>(log.all().keySet())) {
            for (int attempt = 0; attempt < RETRIES; attempt++) {
                LevelLog.Standing cur = log.current(scope).orElse(null);
                if (cur == null || cur.rules().equals(fingerprint)) break;
                V.Cs history = history(scope, cur);
                long t = now().toEpochMilli();
                int target = cur.rung();
                String why = "";
                if (target > policy.ceiling()) {
                    target = policy.ceiling();
                    why = "ceiling lowered to " + policy.nameOf(target);
                }
                for (int r = target; r >= 1; r--) {
                    if (!policy.climbs(r, history, t)) {
                        why = (why.isEmpty() ? "" : why + "; ") + because(policy.explainClimb(r, history, t), false);
                        target = r - 1;
                    } else if (policy.slip(r, history, t).isPresent()) {
                        why = (why.isEmpty() ? "" : why + "; ") + because(policy.explainSlip(r, history, t), true);
                        target = r - 1;
                    } else {
                        break;
                    }
                }
                boolean demoted = target < cur.rung();
                String reason = demoted ? "rules changed: " + why : "rules changed: the standing still holds";
                LevelLog.Standing next = new LevelLog.Standing(target, policy.nameOf(target), cur.version() + 1, cur.epoch(), cur.identity(), cur.servedModel(),
                        !demoted && cur.forced(), now(), reason, fingerprint);
                if (log.write(scope, cur.version(), next, demoted ? "demoted" : "rules", cur.rungName(), "runtime", now(), policy.rulesText())) {
                    if (demoted) out.add(new Change("demoted", scope, cur.rungName(), next.rungName(), reason, "runtime", false));
                    break;
                }
            }
        }
        return out;
    }
}
