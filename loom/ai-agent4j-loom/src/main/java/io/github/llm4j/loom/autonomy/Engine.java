package io.github.llm4j.loom.autonomy;

import io.github.llm4j.loom.ast.DecisionDef;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Everything that changes where a ladder stands, over a ledger and a level store: moving a level up or down, starting an epoch, a hand-set
 * level, a freeze, an outcome. The executor and the {@code weave autonomy} commands both go through here, so a change is made, recorded and
 * explained the same way whoever asked for it.
 */
public final class Engine {

    /** One change worth telling someone about. {@code kind}: demoted, promoted, proposed, epoch, set, rejected. */
    public record Change(String kind, String scope, Level from, Level to, String reason, String by, boolean forced) {
        public String sentence() {
            return switch (kind) {
                case "proposed" -> "promotion to " + to.word() + " proposed for " + shown(scope) + ": " + reason;
                case "rejected" -> "promotion to " + to.word() + " rejected for " + shown(scope) + ": " + reason;
                default -> shown(scope) + " moved from " + from.word() + " to " + to.word() + " (" + kind + (forced ? ", forced" : "") + "): " + reason;
            };
        }

        private static String shown(String scope) {
            return scope.isEmpty() ? "the decision" : "scope " + scope;
        }
    }

    private static final int RETRIES = 5;

    private final DecisionDef def;
    private final Ledger ledger;
    private final LevelStore levels;
    private final Clock clock;
    private final Ladder ladder;

    public Engine(DecisionDef def, Ledger ledger, LevelStore levels, Clock clock) {
        this.def = def;
        this.ledger = ledger;
        this.levels = levels;
        this.clock = clock;
        this.ladder = new Ladder(def, ledger, clock);
    }

    public Ladder ladder() {
        return ladder;
    }

    public DecisionDef def() {
        return def;
    }

    public Ledger ledger() {
        return ledger;
    }

    public LevelStore levels() {
        return levels;
    }

    private Instant now() {
        return clock.instant();
    }

    // ---- reading -------------------------------------------------------------------------------------------------------

    /** The scope's state, created at the declared start level the first time the scope is seen. */
    public LevelState state(String scope, String identity) {
        for (int i = 0; i < RETRIES; i++) {
            Optional<LevelState> found = levels.get(def.getName(), scope);
            if (found.isPresent()) return found.get();
            LevelState first = new LevelState(def.getStartAt(), 1, identity, false, now(), "first case of this scope", 1);
            if (levels.compareAndSet(def.getName(), scope, null, first)) {
                record(first, null, scope, "first", "runtime");
                return first;
            }
        }
        return levels.get(def.getName(), scope).orElseThrow(() -> new IllegalStateException("cannot create the level of scope " + scope));
    }

    /** The level a case runs at: the earned level, held at the ceiling, and at {@code suggest} at most while frozen. */
    public Level effective(LevelState state) {
        Level level = Level.min(state.level(), def.getCeiling());
        if (levels.frozen(def.getName())) level = Level.min(level, Level.SUGGEST);
        return level;
    }

    /** True when a hand-set level is not yet supported by the evidence (shown as forced until it is). */
    public boolean unearned(String scope, LevelState state) {
        return state.forced() && !supported(scope, state.epoch(), Level.WATCH, state.level());
    }

    // ---- moving --------------------------------------------------------------------------------------------------------

    private void record(LevelState to, Level from, String scope, String kind, String by) {
        Rec r = new Rec(def.getName() + "#level#" + scope + "#" + to.version(), def.getName(), Rec.LEVEL, now(), "", 1,
                Rec.map("scope", scope, "from", from == null ? null : from.word(), "to", to.level().word(), "kind", kind, "reason", to.reason(), "by", by,
                        "forced", to.forced() ? true : null, "epoch", to.epoch(), "identity", to.identity(), "version", to.version()));
        ledger.append(r);
    }

    /** A new epoch for the scope (the agent behind the decision changed), starting at {@code level}; null when someone else got there first. */
    public Change newEpoch(String scope, LevelState old, String identity, Level level, String reason) {
        LevelState next = old.withEpoch(level, old.epoch() + 1, identity, now(), reason);
        if (!levels.compareAndSet(def.getName(), scope, old, next)) return null;
        record(next, old.level(), scope, "epoch", "runtime");
        return new Change("epoch", scope, old.level(), level, reason, "runtime", false);
    }

    /** Applies any demotion that holds, else makes or acts on a promotion that has been earned; the changes made. */
    public List<Change> afterCase(String scope) {
        List<Change> out = new ArrayList<>();
        for (int i = 0; i < RETRIES; i++) {
            Optional<LevelState> found = levels.get(def.getName(), scope);
            if (found.isEmpty()) return out;
            LevelState state = found.get();
            Optional<Ladder.Demotion> demotion = ladder.demotion(scope, state);
            if (demotion.isPresent()) {
                Ladder.Demotion d = demotion.get();
                LevelState next = state.withLevel(d.to(), false, now(), d.reason());
                if (levels.compareAndSet(def.getName(), scope, state, next)) {
                    record(next, state.level(), scope, "demoted", "runtime");
                    out.add(new Change("demoted", scope, state.level(), d.to(), d.reason(), "runtime", false));
                    return out;
                }
                continue; // beaten to it: look again at the new state
            }
            Ladder.Progress progress = ladder.progress(scope, state);
            if (!ladder.proposalDue(scope, progress, state)) return out;
            String why = String.format("%d cases, agreement lower bound %.1f%% (raw %.1f%%)", progress.figures().cases(), progress.figures().lowerBound() * 100, progress.figures().rate() * 100);
            if (def.isAutomatic()) {
                LevelState next = state.withLevel(progress.next(), false, now(), why);
                if (levels.compareAndSet(def.getName(), scope, state, next)) {
                    record(next, state.level(), scope, "promoted", "runtime");
                    out.add(new Change("promoted", scope, state.level(), progress.next(), why, "runtime", false));
                    return out;
                }
                continue;
            }
            int cases = ladder.evidence(scope, state.epoch()).size();
            ledger.append(new Rec(def.getName() + "#promotion#" + scope + "#" + progress.next().word() + "#" + state.epoch() + "#" + cases, def.getName(), Rec.PROMOTION_PROPOSED, now(), "", 1,
                    Rec.map("scope", scope, "to", progress.next().word(), "cases", cases, "why", why, "approver", def.getApprover())));
            out.add(new Change("proposed", scope, state.level(), progress.next(), why + "; waiting for " + def.getApprover(), "runtime", false));
            return out;
        }
        return out;
    }

    /** The open promotion proposal of the scope, if any. */
    public Optional<Ladder.Proposal> openProposal(String scope) {
        return ladder.proposals(scope).stream().filter(Ladder.Proposal::open).findFirst();
    }

    /** Approves the open proposal of the scope and moves the level. */
    public Change approve(String scope, String by, String reason) {
        Ladder.Proposal p = openProposal(scope).orElseThrow(() -> new IllegalStateException("there is no open promotion proposal for " + (scope.isEmpty() ? "this decision" : "scope " + scope)));
        LevelState state = levels.get(def.getName(), scope).orElseThrow(() -> new IllegalStateException("no such scope: " + scope));
        if (def.getApprover() != null && !def.getApprover().equals(by)) throw new IllegalStateException("only " + def.getApprover() + " may approve moving up (you said " + by + ")");
        if (state.level().compareTo(p.to()) >= 0) throw new IllegalStateException("the scope is already at " + state.level().word());
        LevelState next = state.withLevel(p.to(), false, now(), "approved by " + by + ": " + reason);
        if (!levels.compareAndSet(def.getName(), scope, state, next)) throw new IllegalStateException("the level changed while you were approving; look again");
        ledger.append(new Rec(p.id() + "#approved", def.getName(), Rec.PROMOTION_DECIDED, now(), "", 1, Rec.map("proposal", p.id(), "result", "approved", "by", by, "reason", reason)));
        record(next, state.level(), scope, "promoted", by);
        return new Change("promoted", scope, state.level(), p.to(), "approved by " + by + ": " + reason, by, false);
    }

    /** Rejects the open proposal; it is not asked again until the evidence has grown by a fifth of the rule's cases. */
    public Change reject(String scope, String by, String reason) {
        Ladder.Proposal p = openProposal(scope).orElseThrow(() -> new IllegalStateException("there is no open promotion proposal for " + (scope.isEmpty() ? "this decision" : "scope " + scope)));
        if (def.getApprover() != null && !def.getApprover().equals(by)) throw new IllegalStateException("only " + def.getApprover() + " may decide on moving up (you said " + by + ")");
        ledger.append(new Rec(p.id() + "#rejected", def.getName(), Rec.PROMOTION_DECIDED, now(), "", 1, Rec.map("proposal", p.id(), "result", "rejected", "by", by, "reason", reason)));
        LevelState state = levels.get(def.getName(), scope).orElse(null);
        return new Change("rejected", scope, state == null ? Level.WATCH : state.level(), p.to(), reason, by, false);
    }

    /**
     * Sets a level by hand. Going up beyond the ceiling or beyond what the evidence supports needs {@code force}, and is then marked forced
     * until the evidence catches up; coming down never needs it.
     */
    public Change set(String scope, Level to, boolean force, String reason, String by) {
        LevelState state = levels.get(def.getName(), scope).orElseThrow(() -> new IllegalStateException("no such scope: \"" + scope + "\" (scopes seen: "
                + String.join(", ", levels.scopes(def.getName()).keySet()) + ")"));
        if (to.compareTo(state.level()) > 0) {
            boolean beyondCeiling = to.compareTo(def.getCeiling()) > 0;
            boolean unsupported = !supported(scope, state.epoch(), state.level(), to);
            if ((beyondCeiling || unsupported) && !force) {
                throw new IllegalStateException("moving " + scope + " up to " + to.word() + " is " + (beyondCeiling ? "above the ceiling (never go above " + def.getCeiling().word() + ")" : "not supported by the evidence")
                        + "; add --force to do it anyway (it is then shown as forced)");
            }
            LevelState next = new LevelState(to, state.epoch(), state.identity(), beyondCeiling || unsupported, now(), reason, state.version() + 1);
            if (!levels.compareAndSet(def.getName(), scope, state, next)) throw new IllegalStateException("the level changed while you were changing it; look again");
            record(next, state.level(), scope, "set", by);
            return new Change("set", scope, state.level(), to, reason, by, next.forced());
        }
        LevelState next = state.withLevel(to, false, now(), reason);
        if (!levels.compareAndSet(def.getName(), scope, state, next)) throw new IllegalStateException("the level changed while you were changing it; look again");
        record(next, state.level(), scope, "set", by);
        return new Change("set", scope, state.level(), to, reason, by, false);
    }

    /** True when every step from {@code from} up to {@code to} has been earned by the scope's blind evidence, ceiling aside. */
    private boolean supported(String scope, int epoch, Level from, Level to) {
        for (int i = from.ordinal() + 1; i <= to.ordinal(); i++) {
            Ladder.Progress p = ladder.evaluate(scope, epoch, Level.values()[i]);
            if (!p.eligible()) return false;
        }
        return true;
    }

    public void freeze(String decision, String reason) {
        levels.setFreeze(decision, new LevelStore.Freeze(reason, now()));
        ledger.append(new Rec(def.getName() + "#freeze#" + now().toEpochMilli() + "#" + decision, def.getName(), Rec.FROZEN, now(), "", 1, Rec.map("frozen", true, "reason", reason, "scope", decision)));
    }

    public void unfreeze(String decision, String reason) {
        levels.clearFreeze(decision);
        ledger.append(new Rec(def.getName() + "#unfreeze#" + now().toEpochMilli() + "#" + decision, def.getName(), Rec.FROZEN, now(), "", 1, Rec.map("frozen", false, "reason", reason, "scope", decision)));
    }

    /** Records a later fact about a case; a reversal of a verdict the agent made may demote. */
    public List<Change> outcome(String caseId, String result, String note) {
        Case c = ledger.get(def.getName(), caseId).orElseThrow(() -> new IllegalArgumentException("there is no case " + caseId + " in decision " + def.getName()));
        ledger.append(new Rec(caseId + "#outcome#" + result + "#" + (note == null ? "" : Integer.toHexString(note.hashCode())), def.getName(), Rec.OUTCOME, now(), caseId, c.generation(),
                Rec.map("result", result, "note", note)));
        return afterCase(c.scope());
    }
}
