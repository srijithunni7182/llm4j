package io.github.llm4j.loom.autonomy;

import io.github.llm4j.loom.ast.DecisionDef;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The rules of a ladder applied to the ledger: whether the next step up has been earned and, if not, what is missing; and whether a
 * demotion rule holds. It reads the ledger and decides nothing by itself; the executor and the {@code weave autonomy} commands act on its answers.
 */
public final class Ladder {

    /** One part of a rule (cases, days, agreement, dangerous mistakes), with what there is and what is needed. */
    public record Part(String name, boolean met, String have, String need) {
        @Override
        public String toString() {
            return name + ": " + (met ? "met" : "short") + " (" + have + ", needs " + need + ")";
        }
    }

    /** How far a scope is from the next step up. */
    public record Progress(Level next, boolean eligible, List<Part> parts, AgreementStats.Figures figures, String reasonNone) {
        /** The first part that is not met, in the order cases, days, agreement, dangerous mistakes. */
        public Optional<Part> firstMissing() {
            return parts.stream().filter(p -> !p.met()).findFirst();
        }
    }

    /** A demotion rule that holds. */
    public record Demotion(Level to, DecisionDef.DropRule rule, String reason) { }

    /** A promotion proposal as the ledger tells it. */
    public record Proposal(String id, String scope, Level to, int casesThen, boolean open, String result, String by) { }

    private final DecisionDef def;
    private final Ledger ledger;
    private final Clock clock;

    public Ladder(DecisionDef def, Ledger ledger, Clock clock) {
        this.def = def;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** The cases of this scope and epoch that count as evidence: decided blind, current, in the order they happened. */
    public List<Case> evidence(String scope, int epoch) {
        return ledger.cases(def.getName(), c -> c.scope().equals(scope) && c.epoch() == epoch && c.blindEvidence());
    }

    private static AgreementStats.Sample sample(Case c) {
        return new AgreementStats.Sample(c.proposal(), c.verdict(), c.malformed(), c.reversed());
    }

    private static <T> List<T> latest(List<T> list, int n) {
        return n <= 0 || list.size() <= n ? list : list.subList(list.size() - n, list.size());
    }

    /** The figures of the latest {@code window} blind cases of the scope's current epoch. */
    public AgreementStats.Figures figures(String scope, int epoch) {
        return AgreementStats.of(latest(evidence(scope, epoch), def.getWindow()).stream().map(Ladder::sample).toList(), def.getDangerous());
    }

    /** How the scope stands against the rule for its next step up (judged on the latest window of blind evidence). */
    public Progress progress(String scope, LevelState state) {
        Level next = state.level() == Level.ACT ? null : Level.values()[state.level().ordinal() + 1];
        List<Case> all = evidence(scope, state.epoch());
        AgreementStats.Figures figures = AgreementStats.of(latest(all, def.getWindow()).stream().map(Ladder::sample).toList(), def.getDangerous());
        if (next == null) return new Progress(null, false, List.of(), figures, "already at act");
        if (!next.atLeast(Level.SUGGEST) || def.getCeiling().compareTo(next) < 0) {
            return new Progress(next, false, List.of(), figures, "the script says never go above " + def.getCeiling().word());
        }
        DecisionDef.UpRule rule = def.getUpRules().get(next);
        if (rule == null) return new Progress(next, false, List.of(), figures, "the script has no rule for moving up to " + next.word());

        List<Part> parts = new ArrayList<>();
        parts.add(new Part("cases", figures.cases() >= rule.cases() && all.size() >= rule.cases(), all.size() + " cases", rule.cases() + " cases"));
        if (rule.days() > 0) {
            long span = all.isEmpty() ? 0 : Duration.between(all.get(0).at(), clock.instant()).toDays();
            parts.add(new Part("days", span >= rule.days(), span + " days", rule.days() + " days"));
        }
        double bound = figures.lowerBound() * 100;
        parts.add(new Part("agreement", bound >= rule.agreeingAtLeast(), String.format("%.1f%% (lower bound of %.1f%% over %d cases)", bound, figures.rate() * 100, figures.cases()),
                String.format("%.0f%%", rule.agreeingAtLeast())));
        if (rule.noDangerous()) parts.add(new Part("dangerous mistakes", figures.dangerous() == 0, figures.dangerous() + " in the window", "none"));
        if (rule.dangerousAtMost() != null) {
            parts.add(new Part("dangerous mistakes", figures.dangerousRate() * 100 <= rule.dangerousAtMost(), String.format("%.1f%%", figures.dangerousRate() * 100),
                    String.format("at most %.1f%%", rule.dangerousAtMost())));
        }
        return new Progress(next, parts.stream().allMatch(Part::met), parts, figures, null);
    }

    /** The demotion rule that holds for this scope, the one that drops it furthest; empty when none does or the scope is already below it. */
    public Optional<Demotion> demotion(String scope, LevelState state) {
        List<Case> current = ledger.cases(def.getName(), c -> c.scope().equals(scope) && c.epoch() == state.epoch() && !c.superseded());
        List<Case> blind = evidence(scope, state.epoch());
        Demotion worst = null;
        for (DecisionDef.DropRule rule : def.getDropRules()) {
            if (state.level().compareTo(rule.to()) <= 0) continue;
            String why = null;
            switch (rule.count()) {
                case DANGEROUS_MISTAKES -> {
                    AgreementStats.Figures f = AgreementStats.of(latest(blind, rule.inCases()).stream().map(Ladder::sample).toList(), def.getDangerous());
                    if (f.dangerous() >= rule.n()) why = f.dangerous() + " dangerous mistakes in the last " + f.cases() + " cases";
                }
                case REVERSALS -> {
                    long n = latest(current.stream().filter(Case::decidedByAgent).toList(), rule.inCases()).stream().filter(Case::reversed).count();
                    if (n >= rule.n()) why = n + " reversals in the last " + rule.inCases() + " cases";
                }
                case UNUSABLE_PROPOSALS -> {
                    long n = latest(current.stream().filter(c -> c.proposal() != null).toList(), rule.inCases()).stream().filter(Case::malformed).count();
                    if (n >= rule.n()) why = n + " unusable proposals in the last " + rule.inCases() + " cases";
                }
                case AGREEMENT_BELOW -> {
                    AgreementStats.Figures f = AgreementStats.of(latest(blind, def.getWindow()).stream().map(Ladder::sample).toList(), def.getDangerous());
                    if (f.cases() >= MIN_FOR_FLOOR && f.rate() * 100 < rule.percent()) {
                        why = String.format("agreement %.1f%% over the last %d cases is below %.0f%%", f.rate() * 100, f.cases(), rule.percent());
                    }
                }
            }
            if (why != null && (worst == null || rule.to().compareTo(worst.to()) < 0)) worst = new Demotion(rule.to(), rule, why);
        }
        return Optional.ofNullable(worst);
    }

    /** A floor on agreement is only judged once there are enough cases for the rate to mean something. */
    public static final int MIN_FOR_FLOOR = 20;

    // ---- promotion proposals, as the ledger tells them -------------------------------------------------------------

    /** The proposals of a scope, oldest first, each with its answer if it has one. */
    public List<Proposal> proposals(String scope) {
        List<Rec> records = ledger.records(def.getName());
        List<Proposal> out = new ArrayList<>();
        for (Rec r : records) {
            if (!Rec.PROMOTION_PROPOSED.equals(r.kind()) || !scope.equals(r.str("scope"))) continue;
            String result = null, by = null;
            for (Rec a : records) {
                if (Rec.PROMOTION_DECIDED.equals(a.kind()) && r.id().equals(a.str("proposal"))) {
                    result = a.str("result");
                    by = a.str("by");
                }
            }
            out.add(new Proposal(r.id(), scope, Level.of(r.str("to")), r.num("cases") == null ? 0 : r.num("cases").intValue(), result == null, result, by));
        }
        return out;
    }

    /**
     * True when a proposal should be made now: the step up is earned, none is open for it, and a rejected one is only asked again once the
     * evidence has grown by a fifth of the rule's cases.
     */
    public boolean proposalDue(String scope, Progress progress, LevelState state) {
        if (!progress.eligible() || progress.next() == null) return false;
        int cases = evidence(scope, state.epoch()).size();
        DecisionDef.UpRule rule = def.getUpRules().get(progress.next());
        for (Proposal p : proposals(scope)) {
            if (p.to() != progress.next()) continue;
            if (p.open()) return false;
            if ("rejected".equals(p.result()) && cases < p.casesThen() + Math.max(1, (int) Math.ceil(rule.cases() * 0.2))) return false;
        }
        return true;
    }
}
