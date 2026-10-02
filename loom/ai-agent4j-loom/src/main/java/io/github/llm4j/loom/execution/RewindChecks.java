package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.CheckpointStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ParallelStmt;
import io.github.llm4j.loom.ast.RewindStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Load-time checks for {@code checkpoint} and {@code rewind}: names, where a rewind may point, and which steps between a checkpoint and a
 * rewind can change something outside the run (so the author says what a rewind should do about it).
 */
final class RewindChecks {

    /** What an agent's tool can do to the world. */
    enum Reach { NONE, EFFECT, UNKNOWN }

    private static final Set<String> READ_ONLY_KINDS = Set.of("sql", "calculator", "datetime", "current_time", "web_search", "serpapi", "duckduckgo", "google_search");
    private static final Set<String> EFFECT_KINDS = Set.of("webhook", "email", "shell");

    private final LoomScript script;
    private final ScriptValidator.Checker checker;

    private RewindChecks(LoomScript script, ScriptValidator.Checker checker) {
        this.script = script;
        this.checker = checker;
    }

    static void run(ScriptValidator.Checker checker) {
        RewindChecks checks = new RewindChecks(checker.script(), checker);
        for (WorkflowDef w : checker.script().getWorkflows()) checks.workflow(w);
    }

    /** One block of statements as the walk sees it: the checkpoints before the current point, and whether a branch boundary hides what is outside. */
    private record Scope(List<Statement> statements, Set<String> reached, Scope outer, boolean barrier) { }

    private void workflow(WorkflowDef w) {
        Set<String> names = new LinkedHashSet<>();
        Set<String> declared = new LinkedHashSet<>();
        StatementWalker.walk(w.getStatements(), s -> {
            if (s instanceof CheckpointStmt c) {
                if (c.getName().equals("start")) checker.error(c.getLine(), "checkpoint start", "\"start\" is the name of the point before the first statement; pick another name");
                else if (!names.add(c.getName())) checker.error(c.getLine(), "checkpoint " + c.getName(), "a checkpoint named " + c.getName() + " already exists in workflow " + w.getName());
                declared.add(c.getName());
            }
        });
        Set<String> root = new LinkedHashSet<>();
        root.add("start");
        block(w, w.getStatements(), new Scope(w.getStatements(), root, null, false), declared);
    }

    private void block(WorkflowDef w, List<Statement> statements, Scope scope, Set<String> declared) {
        for (int i = 0; i < statements.size(); i++) {
            Statement s = statements.get(i);
            if (s instanceof CheckpointStmt c) {
                scope.reached().add(c.getName());
            } else if (s instanceof RewindStmt r) {
                rewind(w, r, scope, declared, statements, i);
            }
            for (List<Statement> nested : StatementWalker.nested(s)) {
                boolean barrier = s instanceof ParallelStmt || s instanceof ForEachStmt;
                block(w, nested, new Scope(nested, new LinkedHashSet<>(), scope, barrier), declared);
            }
        }
    }

    private void rewind(WorkflowDef w, RewindStmt r, Scope scope, Set<String> declared, List<Statement> statements, int index) {
        String construct = "rewind to " + r.getTarget();
        Scope owner = null;
        boolean hidden = false;
        for (Scope s = scope; s != null; s = s.outer()) {
            if (s.reached().contains(r.getTarget())) { owner = s; break; }
            if (s.barrier()) { hidden = true; break; }
        }
        if (owner == null) {
            if (hidden && (declared.contains(r.getTarget()) || r.getTarget().equals("start"))) {
                checker.error(r.getLine(), construct, "a rewind can't leave the parallel branch or for each body it is in; put the checkpoint inside it");
            } else if (declared.contains(r.getTarget())) {
                checker.error(r.getLine(), construct, "checkpoint " + r.getTarget() + " must come earlier, in the same block as the rewind or in one around it");
            } else {
                checker.error(r.getLine(), construct, "there is no checkpoint named " + r.getTarget() + " in workflow " + w.getName() + " (\"start\" is always there)");
            }
            return;
        }
        effects(r, owner, statements, index, construct);
    }

    /** Which tools can the steps from the checkpoint to the rewind reach? */
    private void effects(RewindStmt r, Scope owner, List<Statement> statements, int index, String construct) {
        // the region is the owner block's statements after the checkpoint, up to the statement that holds (or is) the rewind
        List<Statement> region = new ArrayList<>();
        int start = 0;
        if (!r.getTarget().equals("start")) {
            for (int i = 0; i < owner.statements().size(); i++) {
                if (owner.statements().get(i) instanceof CheckpointStmt c && c.getName().equals(r.getTarget())) { start = i + 1; break; }
            }
        }
        for (int i = start; i < owner.statements().size(); i++) {
            Statement s = owner.statements().get(i);
            region.add(s);
            if (contains(s, r)) break;
        }
        Set<String> agents = new LinkedHashSet<>();
        StatementWalker.walk(region, s -> {
            if (s instanceof DelegateStmt d) agents.add(d.getTargetAgent());
            else if (s instanceof HandoffStmt h) agents.add(h.getTargetAgent());
            else if (s instanceof BroadcastStmt b) agents.addAll(b.getTargetAgents());
            else if (s instanceof CallStmt) agents.add("*");
        });
        List<String> risky = new ArrayList<>();
        boolean unapproved = false;
        for (AgentDef a : script.getAgents()) {
            if (!agents.contains(a.getName()) && !agents.contains("*")) continue;
            if (a.getMemory() != null && a.getMemory().getValues().containsKey("facts")) {
                checker.warn(r.getLine(), construct, "agent " + a.getName() + " keeps facts in memory, and a rewind does not undo what it saves");
            }
            for (String tool : a.getTools()) {
                Reach reach = reach(tool);
                if (reach == Reach.NONE) continue;
                risky.add(tool);
                if (!a.isApproveAll() && !a.getApprove().contains(tool) && !unattended(tool)) unapproved = true;
            }
        }
        if (risky.isEmpty()) return;
        if (!r.isEffectsStated()) {
            checker.warn(r.getLine(), construct, "the steps it goes back over can change things outside the run (" + String.join(", ", risky)
                    + "); say what a rewind should do about that: side effects: ask first | keep | repeat (ask first is the default)");
        }
        if (r.getEffects() == RewindStmt.Effects.REPEAT && unapproved) {
            checker.error(r.getLine(), construct, "side effects: repeat would run " + String.join(", ", risky)
                    + " again, so each of them must be approved by the agent that uses it (approve: [...]) or declared unattended: true");
        }
    }

    private static boolean contains(Statement parent, Statement target) {
        if (parent == target) return true;
        boolean[] found = {false};
        StatementWalker.walk(List.of(parent), s -> { if (s == target) found[0] = true; });
        return found[0];
    }

    private ToolDef declaration(String tool) {
        for (ToolDef d : script.getTools()) if (d.getName().equals(tool)) return d;
        return null;
    }

    private boolean unattended(String tool) {
        ToolDef d = declaration(tool);
        return d != null && d.getOptions().containsKey("unattended") && "true".equals(d.getOptions().get("unattended").value());
    }

    private Reach reach(String tool) {
        ToolDef d = declaration(tool);
        String kind = d == null ? tool : d.getKind();
        if (READ_ONLY_KINDS.contains(kind)) return Reach.NONE;
        if (EFFECT_KINDS.contains(kind)) return Reach.EFFECT;
        if (kind.equals("file")) {
            var mode = d == null ? null : d.getOptions().get("mode");
            return mode != null && "read".equals(mode.value()) ? Reach.NONE : (mode == null ? Reach.NONE : Reach.EFFECT);
        }
        if (kind.equals("http")) {
            var methods = d == null ? null : d.getOptions().get("methods");
            if (methods == null) return Reach.NONE;
            return methods.value().toUpperCase().replace(" ", "").equals("GET") ? Reach.NONE : Reach.EFFECT;
        }
        return Reach.UNKNOWN;
    }
}
