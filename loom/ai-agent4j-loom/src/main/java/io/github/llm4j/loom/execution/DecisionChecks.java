package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.CheckpointStmt;
import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.autonomy.Level;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Load-time checks for {@code decision} and {@code decide}: every problem names a line and says what to write instead, so the script can be
 * fixed without reading the runtime.
 */
final class DecisionChecks {

    private final LoomScript script;
    private final ScriptValidator.Checker c;

    private DecisionChecks(ScriptValidator.Checker checker) {
        this.script = checker.script();
        this.c = checker;
    }

    static void run(ScriptValidator.Checker checker) {
        DecisionChecks checks = new DecisionChecks(checker);
        Set<String> names = new HashSet<>();
        for (DecisionDef d : checker.script().getDecisions()) {
            if (!names.add(d.getName())) checker.error(d.getLine(), "decision " + d.getName(), "a decision named " + d.getName() + " is already declared");
            checks.decision(d);
        }
        for (WorkflowDef w : checker.script().getWorkflows()) checks.workflow(w, names);
    }

    // ---- the declaration -----------------------------------------------------------------------------------------

    private void decision(DecisionDef d) {
        String who = "decision " + d.getName();
        int line = d.getLine();
        AgentDef agent = script.getAgents().stream().filter(a -> a.getName().equals(d.getAgent())).findFirst().orElse(null);
        if (d.getAgent() == null) c.error(line, who, "say which agent proposes: proposed by: AgentName");
        else if (agent == null) c.error(line, who, "agent " + d.getAgent() + " is not defined (proposed by: " + d.getAgent() + ")");
        else schema(d, agent);

        if (d.getChoices().size() < 2) c.error(line, who, "a decision needs at least two choices, such as: choices: approve, reject");
        Set<String> seen = new HashSet<>();
        for (String choice : d.getChoices()) if (!seen.add(choice)) c.error(line, who, "the choice " + choice + " is listed twice");
        if (d.getChoices().size() >= 2 && !d.getChoices().contains("escalate")) {
            c.warn(line, who, "there is no choice called escalate; an unusable proposal will be recorded as escalate, which is not one of the choices");
        }
        for (DecisionDef.Mistake m : d.getDangerous()) {
            if (!d.getChoices().contains(m.proposed())) c.error(line, who, "dangerous mistake: " + m.proposed() + " is not one of the choices");
            if (!d.getChoices().contains(m.decided())) c.error(line, who, "dangerous mistake: " + m.decided() + " is not one of the choices");
        }
        if (d.getAsk() == null) c.error(line, who, "say who decides: ask: someone");
        if (d.getKeepDays() < 0) c.error(line, who, "keep records for needs a number of days");
        if (d.getWindow() < 1) c.error(line, who, "judge on the latest N cases needs N of at least 1");
        if (d.getStaleDays() < 1) c.error(line, who, "flag cases with no verdict after N days needs N of at least 1");
        if (d.getTellTool() != null && !c.context().registeredTools().contains(d.getTellTool())) {
            c.error(line, who, "tell " + d.getTellTool() + " when trust changes: tool " + d.getTellTool() + " is not declared (tool " + d.getTellTool() + " { use: … })");
        }
        if (d.getOnChange() == DecisionDef.OnChange.KEEP_TRUST && d.getCeiling().compareTo(Level.SUGGEST) > 0) {
            c.warn(line, who, "keep the trust with never go above " + d.getCeiling().word() + " lets a new agent act on an old agent's record; keep the trust is meant for never go above suggest");
        }
        if (agent != null && agent.getModel() != null && agent.getModel().toLowerCase(java.util.Locale.ROOT).contains("latest")) {
            c.warn(line, who, "agent " + agent.getName() + " uses the model alias " + agent.getModel() + "; a silent change of model behind an alias cannot be detected unless the provider's reply names the model");
        }
        trust(d);
    }

    private void schema(DecisionDef d, AgentDef agent) {
        String who = "decision " + d.getName();
        SchemaDef s = agent.getOutputSchema();
        if (s == null || s.getType() != SchemaDef.Type.OBJECT || s.getFields() == null) {
            c.error(d.getLine(), who, "agent " + agent.getName() + " can't produce a proposal: give it output_schema: { choice: string, reasoning: string } (and optionally confidence: number)");
            return;
        }
        for (String field : List.of("choice", "reasoning")) {
            SchemaDef f = s.getFields().get(field);
            if (f == null || (f.getType() != SchemaDef.Type.STRING && f.getType() != SchemaDef.Type.ENUM)) {
                c.error(d.getLine(), who, "agent " + agent.getName() + " can't produce a proposal: its output_schema needs a " + field + " field (a string)");
            }
        }
        SchemaDef confidence = s.getFields().get("confidence");
        if (confidence != null && confidence.getType() != SchemaDef.Type.NUMBER) c.error(d.getLine(), who, "the confidence field of agent " + agent.getName() + " must be a number");
        SchemaDef choice = s.getFields().get("choice");
        if (choice != null && choice.getType() == SchemaDef.Type.ENUM && choice.getEnumValues() != null) {
            for (String v : choice.getEnumValues()) {
                if (!d.getChoices().contains(v)) c.error(d.getLine(), who, "the choice field of agent " + agent.getName() + " allows " + v + ", which is not one of the decision's choices");
            }
        }
    }

    private void trust(DecisionDef d) {
        String who = "decision " + d.getName();
        if (d.getCeiling().compareTo(d.getStartAt()) < 0) {
            c.error(d.getLine(), who, "never go above " + d.getCeiling().word() + " is below start at " + d.getStartAt().word());
        }
        for (DecisionDef.UpRule r : d.getUpRules().values()) {
            String rule = who + ", to " + r.to().word();
            if (r.to() == Level.WATCH) c.error(r.line(), rule, "nothing is below watch to move up from; write to suggest or to act");
            if (r.cases() < 1) c.error(r.line(), rule, "after N cases needs N of at least 1");
            if (r.days() < 0) c.error(r.line(), rule, "days can't be negative");
            if (r.agreeingAtLeast() < 0 || r.agreeingAtLeast() > 100) c.error(r.line(), rule, "agreeing at least needs a percentage from 0% to 100%, not " + r.agreeingAtLeast() + "%");
            if (r.dangerousAtMost() != null && (r.dangerousAtMost() < 0 || r.dangerousAtMost() > 100)) c.error(r.line(), rule, "dangerous mistakes need a percentage from 0% to 100%");
            if (r.to() == Level.ACT && !d.getUpRules().containsKey(Level.SUGGEST)) {
                c.error(r.line(), rule, "there is no rule for moving up to suggest, so act would skip a level; add: to suggest: after N cases over D days, agreeing at least P%");
            }
        }
        if (!d.getUpRules().isEmpty() && d.getApprover() == null && !d.isAutomatic()) {
            c.error(d.getLine(), who, "say who approves moving up: moving up needs approval from: someone  (or: moving up is automatic)");
        }
        if (d.getCeiling().compareTo(d.getStartAt()) > 0) {
            for (Level l : Level.values()) {
                if (l.compareTo(d.getStartAt()) > 0 && l.compareTo(d.getCeiling()) <= 0 && !d.getUpRules().containsKey(l)) {
                    c.warn(d.getLine(), who, "never go above " + d.getCeiling().word() + " but there is no rule for moving up to " + l.word() + ", so it can't get there");
                }
            }
        }
        if (d.getAuditPercent() < 0 || d.getAuditPercent() > 100) c.error(d.getLine(), who, "check N% of cases needs a percentage from 0% to 100%");
        if (d.getAskAfterPerDay() < 0) c.error(d.getLine(), who, "always ask a person after N cases a day needs N of at least 1");
        for (DecisionDef.DropRule r : d.getDropRules()) {
            String rule = who + ", drop to " + r.to().word();
            if (r.to() == Level.ACT) c.error(r.line(), rule, "dropping to act is not dropping; write drop to suggest or drop to watch");
            if (r.count() == DecisionDef.Count.AGREEMENT_BELOW) {
                if (r.percent() < 0 || r.percent() > 100) c.error(r.line(), rule, "agreement falls below needs a percentage from 0% to 100%");
            } else {
                if (r.n() < 1) c.error(r.line(), rule, "how many needs a number of at least 1");
                if (r.inCases() < 1) c.error(r.line(), rule, "the number of cases to look at must be at least 1");
                if (r.n() > r.inCases()) c.error(r.line(), rule, r.n() + " in " + r.inCases() + " cases can never happen");
            }
            if (r.count() == DecisionDef.Count.DANGEROUS_MISTAKES && d.getDangerous().isEmpty()) {
                c.error(r.line(), rule, "dangerous mistakes are counted, but the decision names none: add dangerous mistake: propose approve, person decides reject");
            }
        }
    }

    // ---- the statements -------------------------------------------------------------------------------------------

    private void workflow(WorkflowDef w, Set<String> declared) {
        Set<String> known = new LinkedHashSet<>(w.getParameters());
        walk(w, w.getStatements(), known, declared);
    }

    /** Statements in the order they run; {@code known} grows with every name a statement sets. */
    private void walk(WorkflowDef w, List<Statement> statements, Set<String> known, Set<String> declared) {
        for (int i = 0; i < statements.size(); i++) {
            Statement s = statements.get(i);
            if (s instanceof DecideStmt decide) {
                decide(w, decide, statements, i, known, declared);
                known.add(decide.getVariable());
                known.add(decide.getVariable() + "_proposal");
                known.add(decide.getVariable() + "_level");
                continue;
            }
            if (s instanceof DelegateStmt d) known.add(d.getVariableName());
            else if (s instanceof io.github.llm4j.loom.ast.RunStmt run) known.add(run.getVariableName());
            else if (s instanceof BroadcastStmt b) known.add(b.getVariableName());
            else if (s instanceof HumanPromptStmt h) known.add(h.getVariableName());
            else if (s instanceof CallStmt call && call.getResultVariable() != null) known.add(call.getResultVariable());
            else if (s instanceof CheckpointStmt cp) known.addAll(cp.getStartingWith().keySet());
            Set<String> inside = known;
            if (s instanceof ForEachStmt f) {
                inside = new LinkedHashSet<>(known);
                inside.add(f.getItemName());
            }
            for (List<Statement> nested : StatementWalker.nested(s)) walk(w, nested, new LinkedHashSet<>(inside), declared);
        }
    }

    private static String base(String name) {
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private void decide(WorkflowDef w, DecideStmt stmt, List<Statement> siblings, int index, Set<String> known, Set<String> declared) {
        String who = "decide " + stmt.getDecision();
        DecisionDef d = script.getDecisions().stream().filter(x -> x.getName().equals(stmt.getDecision())).findFirst().orElse(null);
        if (d == null) {
            c.error(stmt.getLine(), who, "there is no decision named " + stmt.getDecision() + " (declare it: decision " + stmt.getDecision() + " { … })");
            return;
        }
        if (d.getGroupBy() != null && !known.contains(base(d.getGroupBy())) && !known.contains(d.getGroupBy())) {
            c.error(stmt.getLine(), who, "group cases by: " + d.getGroupBy() + " is not a variable set before this point in workflow " + w.getName());
        }
        for (String name : d.getRemember()) {
            if (!known.contains(base(name)) && !known.contains(name)) c.error(stmt.getLine(), who, "remember: " + name + " is not a variable set before this point in workflow " + w.getName());
        }
        if (d.getCeiling() == Level.ACT) actingEffects(w, d, stmt, siblings, index);
    }

    /** At act the proposal takes effect with no person; a branch that then changes the world should not lose its last human check unnoticed. */
    private void actingEffects(WorkflowDef w, DecisionDef d, DecideStmt stmt, List<Statement> siblings, int index) {
        RewindChecks reach = new RewindChecks(script, c);
        List<String> unchecked = new ArrayList<>();
        for (int i = index + 1; i < siblings.size(); i++) {
            if (!(siblings.get(i) instanceof AltStmt alt) || !alt.getCondition().contains(stmt.getVariable())) continue;
            List<Statement> branches = new ArrayList<>();
            branches.addAll(alt.getIfBranch() == null ? List.of() : alt.getIfBranch());
            branches.addAll(alt.getElseBranch() == null ? List.of() : alt.getElseBranch());
            StatementWalker.walk(branches, s -> {
                if (!(s instanceof DelegateStmt del)) return;
                for (AgentDef a : script.getAgents()) {
                    if (!a.getName().equals(del.getTargetAgent())) continue;
                    for (String tool : a.getTools()) {
                        if (reach.reach(tool) == RewindChecks.Reach.NONE) continue;
                        if (!a.isApproveAll() && !a.getApprove().contains(tool) && !reach.unattended(tool)) unchecked.add(tool + " (agent " + a.getName() + ")");
                    }
                }
            });
        }
        if (!unchecked.isEmpty()) {
            c.warn(stmt.getLine(), "decide " + d.getName(), "decision " + d.getName() + " may reach act, and what follows it uses " + String.join(", ", unchecked)
                    + " without approval, so reaching act would remove the last human check there; approve the tool (approve: [...]) or declare it unattended: true");
        }
    }
}
