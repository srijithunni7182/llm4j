package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.llm4j.loom.ast.LoomScript;

/**
 * The static side of the boundary (docs/specs/26-policy-boundary, R8): follows each model-written
 * value from where it is bound to the {@code return} of a workflow nobody else {@code call}s, and
 * reports an error for any path with no {@code check} against every declared policy. It uses the
 * same three levels as the runtime ({@link Provenance}), so the two cannot disagree about what
 * counts.
 *
 * <p>It is advice that comes early; the runtime is the guarantee. A path it cannot follow (a
 * dynamic {@code call}, a value a task produces) is left to the runtime. A branch that may run
 * counts as running; a loop body is followed twice.
 */
public final class StaticSinkChecks {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*)}");

    /** What is known of a variable: its tag and where a model wrote it. */
    private record Known(Provenance.Tag tag, int line, String agent) {}

    /** Where a path that leaves unchecked is reported: the line of the return, the place, the words. */
    interface Report {
        void path(int line, String where, String message);
    }

    private final LoomScript script;
    private final Report report;
    private final List<String> declared;
    private final Set<String> reported = new HashSet<>();
    private final Set<String> inProgress = new HashSet<>();

    private StaticSinkChecks(LoomScript script, List<String> declared, Report report) {
        this.script = script;
        this.declared = declared;
        this.report = report;
    }

    static void run(ScriptValidator.Checker c) {
        if (c.script().getPolicies().isEmpty() || !c.context().staticSinkChecks) return;
        List<String> declared = c.script().getPolicies().stream().map(PolicyDef::getName).toList();
        new StaticSinkChecks(c.script(), declared, (line, where, message) -> c.error(line, where, message)).all();
    }

    /**
     * Every place text a model wrote reaches the return of a workflow that nobody {@code call}s,
     * as short descriptions ({@code "Writer (line 5) → return in Main (line 9)"}), for a script
     * that declares no policy. Used by review rule LY07.
     */
    public static List<String> modelTextExits(LoomScript script) {
        List<String> out = new ArrayList<>();
        new StaticSinkChecks(script, List.of("a policy"), (line, where, message) -> out.add(message)).all();
        return out;
    }

    private void all() {
        Set<String> called = new HashSet<>();
        for (WorkflowDef w : script.getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof CallStmt call) called.add(call.getWorkflowName());
                    });
        for (WorkflowDef w : script.getWorkflows()) {
            if (called.contains(w.getName())) continue; // its return is its caller's to judge
            Map<String, Known> env = new HashMap<>();
            for (String p : w.getParameters()) env.put(p, new Known(Provenance.NOT_MODEL, 0, null));
            walk(w, w.getStatements(), env, true);
        }
    }

    // ---- walking

    private Known walk(WorkflowDef w, List<Statement> statements, Map<String, Known> env, boolean entry) {
        Known returned = null;
        for (Statement s : statements) {
            Known r = step(w, s, env, entry);
            if (r != null) returned = r;
        }
        return returned;
    }

    private Known step(WorkflowDef w, Statement s, Map<String, Known> env, boolean entry) {
        if (s instanceof DelegateStmt d && script.getRemotes().stream().anyMatch(r -> r.getName().equals(d.getTargetAgent()))) {
            // what a remote agent wrote is untrusted whatever shape was asked for (the runtime tags it the same way)
            env.put(d.getVariableName(), new Known(Provenance.UNCHECKED, d.getLine(), d.getTargetAgent()));
        } else if (s instanceof io.github.llm4j.loom.ast.AskStmt ask) {
            // an answer from outside the workflow is not something a model has judged
            env.put(ask.getVariable(), new Known(Provenance.UNCHECKED, ask.getLine(), ask.getChannel()));
        } else if (s instanceof DelegateStmt d) {
            AgentDef agent = agent(d.getTargetAgent());
            SchemaDef schema = d.getExpecting() != null ? d.getExpecting() : agent == null ? null : agent.getOutputSchema();
            Provenance.Tag tag =
                    schema != null && schema.getType() == SchemaDef.Type.OBJECT
                            ? Provenance.model(Provenance.textFieldsOf(schema))
                            : Provenance.UNCHECKED;
            env.put(d.getVariableName(), new Known(tag, d.getLine(), d.getTargetAgent()));
        } else if (s instanceof BroadcastStmt b) {
            env.put(b.getVariableName(), new Known(Provenance.UNCHECKED, b.getLine(), String.join(", ", b.getTargetAgents())));
        } else if (s instanceof ExtractStmt x) {
            ExtractorDef def = script.findExtractor(x.getExtractor());
            env.put(x.getVariableName(), new Known(Provenance.model(def == null ? null : Provenance.textFieldsOf(def.getFields())), x.getLine(), x.getExtractor()));
        } else if (s instanceof IntentClassifyStmt i) {
            env.put(i.getVariableName(), new Known(Provenance.NOT_MODEL, 0, null));
        } else if (s instanceof HumanPromptStmt h) {
            env.put(h.getVariableName(), new Known(Provenance.NOT_MODEL, 0, null));
        } else if (s instanceof RunStmt r) {
            if (r.getVariableName() != null) env.put(r.getVariableName(), new Known(Provenance.NOT_MODEL, 0, null));
        } else if (s instanceof CheckStmt k) {
            check(k, env);
        } else if (s instanceof SetStmt set) {
            env.put(set.getName(), derive(set.getValue(), env));
        } else if (s instanceof CallStmt call) {
            env.put(call.getResultVariable(), callResult(call, env));
        } else if (s instanceof ReturnStmt ret) {
            Known value = derive(ret.getValue(), env);
            if (entry) sink(w, ret, value);
            return value;
        } else if (s instanceof AltStmt a) {
            Map<String, Known> yes = new HashMap<>(env);
            Map<String, Known> no = new HashMap<>(env);
            walk(w, a.getIfBranch(), yes, entry);
            if (a.getElseBranch() != null) walk(w, a.getElseBranch(), no, entry);
            merge(env, yes, no);
        } else if (s instanceof LoopStmt l) {
            for (int i = 0; i < 2; i++) walk(w, l.getBody(), env, entry);
        } else if (s instanceof ForEachStmt f) {
            Known item = lookup(f.getCollectionPath(), env);
            env.put(f.getItemName(), new Known(item.tag().level() == Provenance.Level.NOT_MODEL ? Provenance.NOT_MODEL : new Provenance.Tag(item.tag().level(), item.tag().checkedAgainst(), null), item.line(), item.agent()));
            for (int i = 0; i < 2; i++) walk(w, f.getBody(), env, entry);
        } else if (s instanceof ParallelStmt p) {
            walk(w, p.getBody(), env, entry);
        } else if (s instanceof GuardrailStmt g) {
            walk(w, g.getBody(), env, entry);
        }
        return null;
    }

    private void merge(Map<String, Known> into, Map<String, Known> a, Map<String, Known> b) {
        Set<String> names = new LinkedHashSet<>(a.keySet());
        names.addAll(b.keySet());
        for (String n : names) {
            Known x = a.get(n);
            Known y = b.get(n);
            if (x == null) into.put(n, y);
            else if (y == null) into.put(n, x);
            else into.put(n, worse(x, y));
        }
    }

    private static Known worse(Known x, Known y) {
        Provenance.Tag t = Provenance.join(x.tag(), y.tag());
        Known source = x.tag().level().compareTo(y.tag().level()) >= 0 ? x : y;
        return new Known(t, source.line(), source.agent());
    }

    // ---- values

    private Known derive(ValueExpr value, Map<String, Known> env) {
        if (value instanceof ValueExpr.Text t) return fromText(t.text(), env);
        if (value instanceof ValueExpr.Ref r) return lookup(r.path(), env);
        if (value instanceof ValueExpr.Obj o) {
            Known out = new Known(Provenance.NOT_MODEL, 0, null);
            for (ValueExpr field : o.fields().values()) out = worse(out, derive(field, env));
            return out;
        }
        return new Known(Provenance.NOT_MODEL, 0, null);
    }

    private Known fromText(String text, Map<String, Known> env) {
        Known out = new Known(Provenance.NOT_MODEL, 0, null);
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) out = worse(out, lookup(m.group(1), env));
        return out;
    }

    private Known lookup(String path, Map<String, Known> env) {
        int dot = path.indexOf('.');
        String head = dot > 0 ? path.substring(0, dot) : path;
        Known k = env.get(head);
        if (k == null) return new Known(Provenance.NOT_MODEL, 0, null); // not a variable of ours
        if (dot > 0 && k.tag().textFields() != null) {
            String field = path.substring(dot + 1);
            if (field.contains(".")) field = field.substring(0, field.indexOf('.'));
            if (!k.tag().textFields().contains(field)) return new Known(Provenance.NOT_MODEL, 0, null);
        }
        return k;
    }

    private void check(CheckStmt k, Map<String, Known> env) {
        Known before = lookup(k.getSubject(), env);
        boolean fixes = k.getOnViolation() instanceof CheckStmt.Replace;
        Provenance.Tag after;
        if (before.tag().level() == Provenance.Level.NOT_MODEL) after = Provenance.NOT_MODEL;
        else {
            Set<String> passed = new LinkedHashSet<>(k.getPolicies());
            if (before.tag().level() == Provenance.Level.CHECKED) passed.addAll(before.tag().checkedAgainst());
            after = Provenance.checked(passed);
        }
        if (k.getSubject().contains(".")) return; // a field: the runtime marks the path; not followed here
        // `replace with` and `ask person` can put a text no model wrote, but the branch where the
        // original passes is the one that matters, so the subject is judged by the check's policies.
        env.put(k.getSubject(), new Known(after, before.line(), before.agent()));
        if (k.getVerdictVariable() != null)
            env.put(k.getVerdictVariable(), new Known(Provenance.model(Set.of("rules")), 0, null));
    }

    private Known callResult(CallStmt call, Map<String, Known> env) {
        WorkflowDef callee =
                script.getWorkflows().stream()
                        .filter(w -> w.getName().equals(call.getWorkflowName()))
                        .findFirst()
                        .orElse(null);
        if (callee == null || !inProgress.add(callee.getName()))
            return new Known(Provenance.UNCHECKED, call.getLine(), call.getWorkflowName());
        try {
            Map<String, Known> inner = new HashMap<>();
            for (Map.Entry<String, String> arg : call.getArguments().entrySet())
                inner.put(arg.getKey(), fromText(arg.getValue(), env));
            Known r = walk(callee, callee.getStatements(), inner, false);
            return r == null ? new Known(Provenance.NOT_MODEL, 0, null) : r;
        } finally {
            inProgress.remove(callee.getName());
        }
    }

    private void sink(WorkflowDef w, ReturnStmt ret, Known value) {
        if (Provenance.mayLeave(value.tag(), "x", declared)) return;
        String key = w.getName() + ":" + ret.getLine();
        if (!reported.add(key)) return;
        List<String> missing = Provenance.missing(value.tag(), declared);
        report.path(
                ret.getLine(),
                "return in " + w.getName(),
                "a value written by a model"
                        + (value.agent() == null ? "" : " (" + value.agent() + ", line " + value.line() + ")")
                        + " reaches this return without a check against "
                        + String.join(", ", missing)
                        + ". Add: check <variable> against "
                        + missing.get(0)
                        + " using <Evaluator> before the return.");
    }

    private AgentDef agent(String name) {
        return script.getAgents().stream().filter(a -> a.getName().equals(name)).findFirst().orElse(null);
    }
}
