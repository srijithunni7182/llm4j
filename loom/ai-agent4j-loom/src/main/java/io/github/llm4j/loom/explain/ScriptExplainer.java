package io.github.llm4j.loom.explain;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.BudgetDef;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.CheckpointStmt;
import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.GuardrailStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.NoteStmt;
import io.github.llm4j.loom.ast.ObserveStmt;
import io.github.llm4j.loom.ast.ParallelStmt;
import io.github.llm4j.loom.ast.RewindStmt;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Describes a script in plain English, from the script alone: no model, no key, no network, and the same words for the same script. It only
 * states what the script says (which agent is asked what, where a person is asked, what can spend money); it never guesses at intent.
 */
public final class ScriptExplainer {

    private static final int QUOTE = 100;

    private ScriptExplainer() {}

    /** The explanation of {@code script} (or only {@code workflow} when given); {@code name} is the file's name, shown at the top. */
    public static String explain(LoomScript script, String name, String workflow) {
        StringBuilder out = new StringBuilder();
        List<WorkflowDef> workflows = script.getWorkflows().stream().filter(w -> workflow == null || w.getName().equals(workflow)).toList();
        out.append(name).append('\n');
        out.append("  Workflows: ").append(list(script.getWorkflows().stream().map(w -> w.getName() + "(" + String.join(", ", w.getParameters()) + ")").toList(), "none"))
                .append("   Agents: ").append(script.getAgents().size()).append('\n');
        out.append("  ").append(budget(script.getBudget(), "A run may use ")).append('\n');

        out.append("\nAgents\n");
        if (script.getAgents().isEmpty()) out.append("  none\n");
        for (AgentDef a : script.getAgents()) out.append("  ").append(agent(a)).append('\n');

        Facts facts = new Facts();
        for (WorkflowDef w : workflows) {
            out.append('\n').append(w.getName()).append('(').append(String.join(", ", w.getParameters())).append(")\n");
            if (w.getStatements().isEmpty()) out.append("  It has no steps.\n");
            steps(w.getStatements(), 1, true, out, facts);
        }

        out.append("\nWorth knowing\n");
        out.append("  - ").append(facts.models == 0 ? "No step asks a model." : facts.models + " step" + (facts.models == 1 ? " asks" : "s ask") + " a model.").append('\n');
        if (facts.tasks > 0) out.append("  - ").append(facts.tasks).append(" step").append(facts.tasks == 1 ? " runs" : "s run").append(" plain code (a task), with no model.\n");
        out.append("  - ").append(facts.people == 0 ? "No person is asked anything." : "A person is asked at " + facts.people + " step" + (facts.people == 1 ? "" : "s") + ".").append('\n');
        List<String> approving = script.getAgents().stream().filter(a -> a.isApproveAll() || !a.getApprove().isEmpty()).map(AgentDef::getName).toList();
        if (!approving.isEmpty()) out.append("  - A person is asked before a tool is used by: ").append(String.join(", ", approving)).append(".\n");
        List<String> withTools = script.getAgents().stream().filter(a -> !a.getTools().isEmpty()).map(AgentDef::getName).toList();
        if (!withTools.isEmpty()) out.append("  - Agents with tools: ").append(String.join(", ", withTools)).append(". Run weave audit to see what they can reach.\n");
        if (facts.loops > 0) out.append("  - ").append(facts.loops).append(" loop").append(facts.loops == 1 ? " is" : "s are").append(" bounded by a maximum.\n");
        return out.toString();
    }

    private static final class Facts {
        int models;
        int tasks;
        int people;
        int loops;
    }

    private static String agent(AgentDef a) {
        List<String> parts = new ArrayList<>();
        parts.add(a.getModel() == null ? "no model named" : a.getModel());
        if (a.getTemperature() != null) parts.add("temperature " + trim(a.getTemperature()));
        if (a.getPromptRef() != null) parts.add("prompt file \"" + a.getPromptRef() + "\"");
        else if (a.getSystemPrompt() != null && !a.getSystemPrompt().isBlank()) parts.add("an inline prompt");
        parts.add(a.getTools().isEmpty() ? "no tools" : "tools " + String.join(", ", a.getTools()));
        if (a.isApproveAll()) parts.add("a person approves every tool call");
        else if (!a.getApprove().isEmpty()) parts.add("a person approves " + String.join(", ", a.getApprove()));
        if (a.getGuard() != null && a.getGuard().getPii() != null) parts.add("personal data " + a.getGuard().getPii());
        return a.getName() + ": " + String.join("; ", parts) + ".";
    }

    private static String budget(BudgetDef b, String lead) {
        if (b == null) return "No budget is set (a run has no limit of its own).";
        List<String> limits = new ArrayList<>();
        if (b.getTokens() != null) limits.add(String.format(Locale.ROOT, "%,d tokens", b.getTokens()));
        if (b.getCalls() != null) limits.add(b.getCalls() + " model calls");
        if (b.getCost() != null) limits.add(b.getCost().toPlainString() + " in cost");
        return limits.isEmpty() ? "A budget is set without limits." : "Budget: " + lead + String.join(" and ", limits) + ".";
    }

    /** Top-level steps are numbered; steps inside a branch, loop or handler are bulleted under the line that introduces them. */
    private static void steps(List<Statement> body, int indent, boolean numbered, StringBuilder out, Facts facts) {
        int n = 0;
        for (Statement s : body) {
            n++;
            out.append("  ".repeat(indent)).append(numbered ? n + ". " : "- ").append(describe(s, facts)).append('\n');
            nested(s, indent + 1, out, facts);
        }
    }

    private static void nested(Statement s, int indent, StringBuilder out, Facts facts) {
        String pad = "  ".repeat(indent);
        if (s instanceof AltStmt alt) {
            steps(alt.getIfBranch(), indent, false, out, facts);
            if (!alt.getElseBranch().isEmpty()) {
                out.append("  ".repeat(indent - 1)).append("- Otherwise:\n");
                steps(alt.getElseBranch(), indent, false, out, facts);
            }
        } else if (s instanceof LoopStmt loop) {
            steps(loop.getBody(), indent, false, out, facts);
            if (!loop.getOnExhausted().isEmpty()) {
                out.append(pad).append("- If the limit is reached without that happening:\n");
                steps(loop.getOnExhausted(), indent + 1, false, out, facts);
            }
        } else if (s instanceof ForEachStmt each) {
            steps(each.getBody(), indent, false, out, facts);
        } else if (s instanceof ParallelStmt par) {
            steps(par.getBody(), indent, false, out, facts);
        } else if (s instanceof DelegateStmt d && !d.getOnFailure().isEmpty()) {
            out.append(pad).append("- If it fails:\n");
            steps(d.getOnFailure(), indent + 1, false, out, facts);
        } else if (s instanceof RunStmt r && !r.getOnFailure().isEmpty()) {
            out.append(pad).append("- If it fails:\n");
            steps(r.getOnFailure(), indent + 1, false, out, facts);
        } else if (s instanceof GuardrailStmt g) {
            steps(g.getBody(), indent, false, out, facts);
            if (!g.getOnViolation().isEmpty()) {
                out.append(pad).append("- If the check is violated:\n");
                steps(g.getOnViolation(), indent + 1, false, out, facts);
            }
        }
    }

    /** A condition as written in the script, said in words. */
    private static String say(String condition) {
        return condition.replace("==", " is ").replace("!=", " is not ").replace("&&", " and ").replace("||", " or ").replaceAll("\\s+", " ").strip();
    }

    private static String describe(Statement s, Facts facts) {
        if (s instanceof DelegateStmt d) {
            facts.models++;
            String text = d.getTargetAgent() + " is asked: " + quote(d.getPayload()) + kept(d.getVariableName());
            if (d.getExpecting() != null) text += " It must answer in a set shape" + shape(d.getExpecting()) + ".";
            if (d.getRetryCount() > 0) text += " It is retried up to " + d.getRetryCount() + " time" + (d.getRetryCount() == 1 ? "" : "s") + " if it fails.";
            return text;
        }
        if (s instanceof BroadcastStmt b) {
            facts.models += Math.max(1, b.getTargetAgents().size());
            return "Every one of " + String.join(", ", b.getTargetAgents()) + " is asked: " + quote(b.getPayload()) + kept(b.getVariableName());
        }
        if (s instanceof HandoffStmt h) {
            facts.models++;
            return "The work is handed to " + h.getTargetAgent() + ", which ends this path: " + quote(h.getPayload());
        }
        if (s instanceof HumanPromptStmt h) {
            facts.people++;
            return "A person is asked: " + quote(h.getMessage()) + kept(h.getVariableName());
        }
        if (s instanceof NoteStmt n) return "A note is shown: " + quote(n.getMessage());
        if (s instanceof RunStmt r) {
            facts.tasks++;
            return "The task " + r.getTaskName() + " runs (plain code, no model)" + kept(r.getVariableName());
        }
        if (s instanceof CallStmt c) return "The workflow " + c.getWorkflowName() + " is run" + kept(c.getResultVariable());
        if (s instanceof AltStmt a) return "If " + say(a.getCondition()) + ":";
        if (s instanceof LoopStmt l) {
            facts.loops++;
            return "Repeat, at most " + l.getMaxIterations() + " time" + (l.getMaxIterations() == 1 ? "" : "s") + ", until " + say(l.getCondition()) + ":";
        }
        if (s instanceof ForEachStmt f) return (f.isParallel() ? "For each " : "For each ") + f.getItemName() + " in " + f.getCollectionPath() + (f.isParallel() ? " (at the same time):" : ":");
        if (s instanceof ParallelStmt) return "At the same time:";
        if (s instanceof CheckpointStmt c) return "A checkpoint named " + c.getName() + " is saved.";
        if (s instanceof RewindStmt r) return "Go back to " + r.getTarget() + " when " + say(r.getCondition()) + ", at most " + r.getAtMost() + " time" + (r.getAtMost() == 1 ? "" : "s") + ".";
        if (s instanceof DecideStmt d) {
            facts.people++;
            return "The decision " + d.getDecision() + " is made" + kept(d.getVariable());
        }
        if (s instanceof GuardrailStmt g) return "A " + g.getType() + " check applies to:";
        if (s instanceof ObserveStmt o) return "The value of " + o.getExpression() + " is recorded as " + quote(o.getLabel());
        return "A " + s.getClass().getSimpleName().replace("Stmt", "").toLowerCase(Locale.ROOT) + " step.";
    }

    private static String kept(String variable) {
        return variable == null || variable.isBlank() || variable.startsWith("{") ? "." : ". The answer is kept as " + variable + ".";
    }

    private static String shape(SchemaDef schema) {
        if (schema.getFields() != null && !schema.getFields().isEmpty()) return " with " + String.join(", ", schema.getFields().keySet());
        return "";
    }

    private static String quote(String text) {
        String one = text == null ? "" : text.replace("\\n", " ").replaceAll("\\s+", " ").strip();
        if (one.length() > QUOTE) {
            int cut = one.lastIndexOf(' ', QUOTE);
            one = one.substring(0, cut > QUOTE / 2 ? cut : QUOTE) + " …";
        }
        return "\"" + one + "\"";
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String list(List<String> items, String none) {
        return items.isEmpty() ? none : String.join(", ", items);
    }
}
