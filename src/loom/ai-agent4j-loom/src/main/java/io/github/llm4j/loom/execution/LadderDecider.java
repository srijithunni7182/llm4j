package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.RungDef;
import io.github.llm4j.loom.autonomy.Rec;
import io.github.llm4j.loom.autonomy.expr.V;
import io.github.llm4j.loom.ladder.HistoryBuilder;
import io.github.llm4j.loom.ladder.LCase;
import io.github.llm4j.loom.ladder.LadderEngine;
import io.github.llm4j.loom.ladder.LadderPolicy;
import io.github.llm4j.loom.ladder.LevelLog;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Runs a {@code decide} statement whose decision binds a ladder. The agent proposes; the rung says whether a person answers without seeing the
 * proposal, answers with it, or is not asked; the answer (accepted, edited, rejected) and everything that follows is written to the ledger, and the
 * ladder moves on what the record then says. Everything is journaled under the decide step so a resumed run repeats nothing.
 */
final class LadderDecider {

    private static final Logger log = Logger.getLogger(LadderDecider.class.getName());

    private final HarnessExecutor run;
    private final Decider d;
    private final Map<String, LadderEngine> engines = new ConcurrentHashMap<>();

    LadderDecider(HarnessExecutor run, Decider decider) {
        this.run = run;
        this.d = decider;
    }

    /** The engine of a decision, created once; rules that changed since the last run are applied the first time it is built. */
    LadderEngine engine(DecisionDef def) {
        return engines.computeIfAbsent(def.getName(), n -> {
            LadderEngine engine = new LadderEngine(LadderPolicy.of(run.script(), def), d.ledger(), run.clock());
            try {
                for (LadderEngine.Change change : engine.reevaluate()) announce(def, change);
            } catch (RuntimeException e) {
                log.warning("Decision " + def.getName() + ": could not check the ladder against its rules (" + e.getMessage() + ")");
            }
            return engine;
        });
    }

    private static boolean artifact(DecisionDef def) {
        return def.getProposes() != null;
    }

    private static String proposed(Map<String, Object> proposal) {
        Object v = proposal.get("choice") != null ? proposal.get("choice") : proposal.get("value");
        return Decider.text(v);
    }

    // ---- the statement -------------------------------------------------------------------------------------------------------

    void decide(DecideStmt stmt, DecisionDef def, AgentDef agent) {
        String step = run.currentStep();
        RunJournal journal = run.journal();
        String free = run.identityStep();
        String caseId = Decider.text(run.getRunId()) + "/" + free;
        Map<String, String> fields = d.fields(def);
        String scope = d.scopeOf(def);
        LadderEngine engine = engine(def);

        Map<String, Object> plan = planFor(def, engine, caseId, step, scope, fields, null);

        var done = journal.get(step + "#decide-result");
        if (done.isPresent() && done.get().value() instanceof Map<?, ?> m) {
            // a resumed run: the case is decided; make sure the ledger has all of it (a crash may have come between the journal and the ledger)
            Map<String, Object> result = Decider.castMap(m);
            Map<String, Object> proposal = journal.get(step + "#decide-proposal").filter(e -> e.value() instanceof Map<?, ?>).map(e -> Decider.castMap((Map<?, ?>) e.value())).orElse(Map.of());
            openCase(def, caseId, step, free, scope, fields, plan, proposal);
            appendProposed(def, caseId, step, proposal);
            bind(stmt, result);
            appendResponded(def, caseId, step, result);
            afterResponse(def, engine, scope);
            return;
        }

        // 1. the proposal
        Map<String, Object> proposal = journal.get(step + "#decide-proposal").filter(e -> e.value() instanceof Map<?, ?>).map(e -> Decider.castMap((Map<?, ?>) e.value())).orElse(null);
        Decider.Proposing p = null;
        if (proposal == null) {
            boolean hide = RungDef.Mode.of(Decider.text(plan.get("mode"))) == RungDef.Mode.BLIND;
            p = new Decider.Proposing(journal, step, hide);
            run.forgetServedModel(agent.getName());
            proposal = d.propose(def, agent, step, fields, p, io.github.llm4j.loom.autonomy.Level.WATCH);
            String served = run.servedModel(agent.getName());
            proposal.put("servedModel", served == null ? "unreported" : served);
            journal.put(step + "#decide-proposal", new RunJournal.Entry("proposal", proposal));
            // the provider's reply may show the model changed behind an unchanged script: a new epoch starts, and this case is run under it
            plan = planFor(def, engine, caseId, step, scope, fields, Decider.text(proposal.get("servedModel")));
        }
        boolean malformed = Boolean.TRUE.equals(proposal.get("malformed"));
        RungDef.Mode mode = RungDef.Mode.of(Decider.text(plan.get("mode")));
        if (mode == RungDef.Mode.AUTONOMOUS && malformed) {
            plan = new LinkedHashMap<>(plan);
            plan.put("mode", RungDef.Mode.CONFIRM.word());
            plan.put("how", "unusable proposal");
            journal.put(step + "#level", new RunJournal.Entry("level", plan));
            mode = RungDef.Mode.CONFIRM;
        }
        openCase(def, caseId, step, free, scope, fields, plan, proposal);
        appendProposed(def, caseId, step, proposal);
        announceProposal(def, caseId, plan, mode == RungDef.Mode.BLIND, proposal);
        run.stopPoint(step + "#decide-proposal");

        // 2. who decides
        Map<String, Object> result = new LinkedHashMap<>();
        String proposedText = proposed(proposal);
        if (mode == RungDef.Mode.AUTONOMOUS) {
            result.put("kind", "accept");
            result.put("verdict", proposedText);
            result.put("choice", proposal.get("choice"));
            result.put("decider", "agent");
            result.put("shown", false);
            result.put("millis", 0L);
        } else {
            boolean shown = mode == RungDef.Mode.CONFIRM;
            long askedAt = journal.get(step + "#decide-asked-at").map(e -> ((Number) e.value()).longValue()).orElseGet(() -> {
                long now = run.clock().millis();
                journal.put(step + "#decide-asked-at", new RunJournal.Entry("time", now));
                return now;
            });
            if (artifact(def)) answerArtifact(def, step, fields, shown ? proposal : null, proposedText, result);
            else answerChoice(def, step, fields, shown ? proposal : null, proposedText, result);
            result.put("decider", def.getAsk());
            result.put("shown", shown);
            result.put("millis", Math.max(0, run.clock().millis() - askedAt));
        }
        if (p != null) p.flush();
        result.put("proposal", proposedText);
        result.put("level", plan.get("rung"));
        journal.put(step + "#decide-result", new RunJournal.Entry("decision", result));
        bind(stmt, result);
        appendResponded(def, caseId, step, result);
        afterResponse(def, engine, scope);
    }

    private void bind(DecideStmt stmt, Map<String, Object> result) {
        run.setVariable(stmt.getVariable(), Decider.text(result.get("verdict")));
        run.setVariable(stmt.getVariable() + "_proposal", Decider.text(result.get("proposal")));
        run.setVariable(stmt.getVariable() + "_level", Decider.text(result.get("level")));
    }

    // ---- the plan ------------------------------------------------------------------------------------------------------------

    /**
     * The rung and mode this case runs at, read once and journaled so a run resumed after a change finishes the case as it began. Asked again
     * when the proposal reveals a new served model: the epoch it opens may put the case on a lower rung.
     */
    private Map<String, Object> planFor(DecisionDef def, LadderEngine engine, String caseId, String step, String scope, Map<String, String> fields, String served) {
        RunJournal journal = run.journal();
        var known = journal.get(step + "#level");
        if (known.isPresent() && known.get().value() instanceof Map<?, ?> m) {
            Map<String, Object> prior = Decider.castMap(m);
            if (served == null || Decider.text(prior.get("served")).equals(served)) return prior;
        }
        Map<String, Object> pick = new LinkedHashMap<>();
        pick.put("scope", scope);
        try {
            String identity = d.identityOf(def);
            LevelLog.Standing st = engine.standing(scope, identity, served);
            V.Rec current = HistoryBuilder.record(new LCase(caseId, def.getName(), scope, run.clock().instant(), st.rungName(), "", identity, st.epoch(), 1, false,
                    fields, served, null, null, null, null, false, null, List.of(), false, null, null, null, null, false, null, null, List.of()));
            LadderEngine.Plan plan = engine.plan(scope, caseId, st, current, engine.history(scope, st.epoch()), run.clock().millis());
            pick.put("rung", plan.rung());
            pick.put("mode", plan.mode().word());
            pick.put("how", plan.how());
            pick.put("earned", st.rungName());
            pick.put("epoch", st.epoch());
            pick.put("identity", st.identity());
            if (st.forced()) pick.put("forced", true);
            if (served != null) pick.put("served", served);
        } catch (RuntimeException e) {
            // fail closed: a ledger that cannot be read never means a higher rung, and never means the agent acts alone
            log.warning("Decision " + def.getName() + ": the ledger failed (" + e.getMessage() + "); asking a person");
            run.audit("autonomy_degraded", Map.of("decision", def.getName(), "case", caseId, "reason", Decider.text(e.getMessage())));
            pick.put("rung", engine.policy().nameOf(0));
            pick.put("mode", RungDef.Mode.BLIND.word());
            pick.put("how", "degraded");
            pick.put("epoch", 1);
            pick.put("identity", d.identityOf(def));
        }
        journal.put(step + "#level", new RunJournal.Entry("level", pick));
        return pick;
    }

    // ---- the person ----------------------------------------------------------------------------------------------------------

    private void answerChoice(DecisionDef def, String step, Map<String, String> fields, Map<String, Object> shown, String proposedText, Map<String, Object> result) {
        String choice = d.ask(def, step, fields, shown);
        result.put("choice", choice);
        result.put("verdict", choice);
        result.put("kind", choice.equals(proposedText) ? "accept" : "reject");
    }

    private void answerArtifact(DecisionDef def, String step, Map<String, String> fields, Map<String, Object> shown, String proposedText, Map<String, Object> result) {
        if (run.humanInterface() == null) {
            throw new IllegalStateException("decision " + def.getName() + " needs a person to ask (" + def.getAsk() + "), but no human interface is configured");
        }
        StringBuilder q = new StringBuilder();
        q.append(def.getAsk()).append(", please ").append(shown == null ? "write the " + def.getProposes() + " for " : "review the " + def.getProposes() + " for ").append(def.getName()).append('\n');
        fields.forEach((k, v) -> q.append(Decider.neutralise(k)).append(" = ").append(Decider.neutralise(v)).append('\n'));
        if (shown != null) {
            q.append("\nThe agent proposes:\n").append(Decider.neutralise(proposedText)).append("\nIts reasoning: ").append(Decider.neutralise(Decider.text(shown.get("reasoning"))))
                    .append("\n\nAnswer accept, reject, or send the corrected ").append(def.getProposes()).append(".\n");
        }
        String question = q.toString();
        String key = step + "#decide-ask";
        String answer = run.atStep(key, () -> {
            String recorded = run.recordedAnswer(question);
            if (recorded != null) return recorded;
            String got = run.humanInterface().promptHuman(key, question,
                    new io.github.llm4j.loom.runtime.HumanInterface.Hints(io.github.llm4j.loom.runtime.HumanInterface.Hints.Kind.DECIDE, List.of(), def.getAsk()));
            run.recordAnswer(question, got);
            return got;
        });
        String a = answer == null ? "" : answer.strip();
        String word = a.toLowerCase(Locale.ROOT);
        if (shown != null && (word.equals("accept") || word.equals("ok") || word.equals("yes") || word.equals("approve"))) {
            result.put("kind", "accept");
            result.put("verdict", proposedText);
        } else if (shown != null && (word.equals("reject") || word.equals("no"))) {
            result.put("kind", "reject");
            result.put("verdict", "");
        } else if (a.equals(proposedText.strip())) {
            result.put("kind", "accept");
            result.put("verdict", proposedText);
        } else {
            String masked = d.mask(def, a);
            result.put("kind", "edit");
            result.put("final", masked);
            result.put("verdict", masked);
        }
    }

    // ---- the ledger ----------------------------------------------------------------------------------------------------------

    private void openCase(DecisionDef def, String caseId, String step, String free, String scope, Map<String, String> fields, Map<String, Object> plan, Map<String, Object> proposal) {
        int gen = Decider.generationOf(step);
        d.write(def, new Rec(caseId + "#case#" + gen, def.getName(), Rec.CASE, run.clock().instant(), caseId, gen,
                Rec.map("scope", scope, "locator", run.runLocator(), "step", free, "journalStep", step, "fields", fields, "level", plan.get("rung"), "how", plan.get("how"),
                        "identity", plan.get("identity"), "epoch", plan.get("epoch"), "forced", plan.get("forced"),
                        "servedModel", proposal == null ? null : proposal.get("servedModel"))));
    }

    private void appendProposed(DecisionDef def, String caseId, String step, Map<String, Object> proposal) {
        int gen = Decider.generationOf(step);
        d.write(def, new Rec(caseId + "#proposed#" + gen, def.getName(), Rec.PROPOSED, run.clock().instant(), caseId, gen,
                Rec.map("choice", proposal.get("choice"), "value", proposal.get("value"), "reasoning", proposal.get("reasoning"), "confidence", proposal.get("confidence"),
                        "malformed", Boolean.TRUE.equals(proposal.get("malformed")) ? true : null, "flags", proposal.get("flags"))));
    }

    private void appendResponded(DecisionDef def, String caseId, String step, Map<String, Object> result) {
        int gen = Decider.generationOf(step);
        d.write(def, new Rec(caseId + "#responded#" + gen, def.getName(), Rec.RESPONDED, run.clock().instant(), caseId, gen,
                Rec.map("kind", result.get("kind"), "choice", result.get("choice"), "final", result.get("final"), "by", result.get("decider"),
                        "shown", Boolean.TRUE.equals(result.get("shown")), "millis", result.get("millis"))));
    }

    private void afterResponse(DecisionDef def, LadderEngine engine, String scope) {
        try {
            for (LadderEngine.Change change : engine.afterResponse(scope)) announce(def, change);
        } catch (RuntimeException e) {
            log.warning("Decision " + def.getName() + ": could not apply the ladder (" + e.getMessage() + ")");
        }
    }

    // ---- telling people ------------------------------------------------------------------------------------------------------

    private void announceProposal(DecisionDef def, String caseId, Map<String, Object> plan, boolean blind, Map<String, Object> proposal) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decision", def.getName());
        data.put("case", caseId);
        data.put("level", plan.get("rung"));
        if (blind) {
            run.trace(TraceEvent.DECISION, def.getAgent(), "proposal recorded (hidden)", data);
        } else {
            data.put(artifact(def) ? "value" : "choice", proposed(proposal));
            run.trace(TraceEvent.DECISION, def.getAgent(), "proposes " + (artifact(def) ? def.getProposes() : proposed(proposal)), data);
            run.audit("decision_proposed", data);
        }
    }

    private void announce(DecisionDef def, LadderEngine.Change change) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decision", def.getName());
        data.put("scope", change.scope());
        data.put("kind", change.kind());
        data.put("from", change.from());
        data.put("to", change.to());
        data.put("reason", change.reason());
        if (change.forced()) data.put("forced", true);
        run.audit("proposed".equals(change.kind()) ? "promotion_proposed" : "level_changed", data);
        run.trace(TraceEvent.DECISION, def.getAgent(), change.sentence(), data);
        if (def.getTellTool() != null) tell(def, change);
    }

    private void tell(DecisionDef def, LadderEngine.Change change) {
        try {
            io.github.llm4j.agent.Tool tool = run.toolNamed(def.getTellTool());
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("title", "Trust in " + def.getName() + " changed");
            args.put("text", change.sentence());
            run.atStep(run.currentStep() + "#tell:" + change.scope() + ":" + change.kind() + ":" + change.to(), () -> {
                try {
                    return tool.execute(args);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (RuntimeException e) {
            log.warning("Decision " + def.getName() + ": could not tell " + def.getTellTool() + " (" + e.getMessage() + ")");
            run.audit("autonomy_notification_failed", Map.of("decision", def.getName(), "tool", def.getTellTool(), "reason", Decider.text(e.getMessage())));
        }
    }
}
