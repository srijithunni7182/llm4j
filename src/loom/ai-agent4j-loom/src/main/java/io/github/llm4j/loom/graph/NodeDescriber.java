package io.github.llm4j.loom.graph;

import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.CallStmt;
import io.github.llm4j.loom.ast.CheckpointStmt;
import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.GuardrailStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.NoteStmt;
import io.github.llm4j.loom.ast.ObserveStmt;
import io.github.llm4j.loom.ast.ParallelStmt;
import io.github.llm4j.loom.ast.RewindStmt;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.SleepStmt;
import io.github.llm4j.loom.ast.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns one statement into a node: its kind, label, agent and the attributes written on it. It does
 * not know about control flow; {@link GraphBuilder} places the node.
 */
final class NodeDescriber {

    private static final int LABEL_LIMIT = 60;

    private final Map<String, String> decisionLevels;
    private final Map<String, GraphBuilder.ClassifierInfo> classifiers;
    private final GraphBuilder.A2AInfo a2a;

    /**
     * @param decisionLevels decision name to the level it starts at ({@code watch}, {@code
     *     suggest}, {@code act})
     * @param classifiers classifier name to its declaration and backend
     */
    NodeDescriber(
            Map<String, String> decisionLevels,
            Map<String, GraphBuilder.ClassifierInfo> classifiers,
            GraphBuilder.A2AInfo a2a) {
        this.classifiers = classifiers;
        this.decisionLevels = decisionLevels;
        this.a2a = a2a;
    }

    GraphNode describe(String id, Statement statement, String file) {
        SourceRef source =
                statement.getLine() > 0 ? new SourceRef(file, statement.getLine()) : null;
        Described d = describe(statement);
        return new GraphNode(
                id, d.kind, d.label, d.agent, d.bound, source, d.call, false, null, null, d.attrs);
    }

    private Described describe(Statement st) {
        if (st instanceof DelegateStmt d && a2a.remotes().containsKey(d.getTargetAgent())) {
            return remote(d, a2a.remotes().get(d.getTargetAgent()));
        }
        if (st instanceof io.github.llm4j.loom.ast.AskStmt a) {
            return ask(a);
        }
        if (st instanceof DelegateStmt d) {
            return delegate(d);
        }
        if (st instanceof RunStmt r) {
            return run(r);
        }
        if (st instanceof io.github.llm4j.loom.ast.IntentClassifyStmt c) {
            return classify(c);
        }
        if (st instanceof io.github.llm4j.loom.ast.ExtractStmt x) {
            return extract(x);
        }
        if (st instanceof io.github.llm4j.loom.ast.CheckStmt k) {
            return check(k);
        }
        if (st instanceof io.github.llm4j.loom.ast.UseStmt u) {
            return use(u);
        }
        if (st instanceof HandoffStmt h) {
            return new Described(Kinds.HANDOFF, "handoff " + h.getTargetAgent())
                    .agent(h.getTargetAgent())
                    .attrs(
                            Attrs.create()
                                    .text("text", h.getPayload())
                                    .list("attaches", kinds(h.getAttachments())));
        }
        if (st instanceof BroadcastStmt b) {
            return new Described(Kinds.BROADCAST, "broadcast")
                    .attrs(
                            Attrs.create()
                                    .list("agents", b.getTargetAgents())
                                    .list("attaches", kinds(b.getAttachments()))
                                    .text("variable", b.getVariableName())
                                    .budget(b.getBudget())
                                    .text("text", b.getPayload()));
        }
        if (st instanceof ParallelStmt p) {
            return parallel(p);
        }
        if (st instanceof AltStmt a) {
            return new Described(Kinds.ALT, cut(a.getCondition()) + "?")
                    .attrs(Attrs.create().text("condition", a.getCondition()));
        }
        if (st instanceof LoopStmt l) {
            Described d =
                    new Described(Kinds.LOOP, "loop until " + cut(l.getCondition()))
                            .attrs(
                                    Attrs.create()
                                            .text("condition", l.getCondition())
                                            .budget(l.getBudget()));
            d.bound = l.getMaxIterations() > 0 ? l.getMaxIterations() : null;
            return d;
        }
        if (st instanceof ForEachStmt f) {
            Described d =
                    new Described(
                                    Kinds.FOREACH,
                                    "for each " + f.getItemName() + " in " + f.getCollectionPath())
                            .attrs(
                                    Attrs.create()
                                            .text("item", f.getItemName())
                                            .text("collection", f.getCollectionPath())
                                            .flag("parallel", f.isParallel())
                                            .budget(f.getBudget()));
            d.bound = f.getMax() > 0 ? f.getMax() : null;
            return d;
        }
        if (st instanceof HumanPromptStmt h) {
            return new Described(Kinds.HUMAN_PROMPT, "ask a person")
                    .attrs(
                            Attrs.create()
                                    .text("variable", h.getVariableName())
                                    .list("attaches", kinds(h.getAttachments()))
                                    .text("text", h.getMessage()));
        }
        if (st instanceof CheckpointStmt c) {
            return new Described(Kinds.CHECKPOINT, "checkpoint " + c.getName())
                    .attrs(
                            Attrs.create()
                                    .text("name", c.getName())
                                    .map("startingWith", c.getStartingWith()));
        }
        if (st instanceof io.github.llm4j.loom.ast.OutcomeStmt o) {
            return new Described(Kinds.OUTCOME, o.getKind().phrase());
        }
        if (st instanceof RewindStmt r) {
            return new Described(Kinds.REWIND, "rewind to " + r.getTarget())
                    .attrs(
                            Attrs.create()
                                    .text("target", r.getTarget())
                                    .text("condition", r.getCondition())
                                    .positive("atMost", r.getAtMost())
                                    .text("effects", r.getEffects().phrase())
                                    .map("carrying", r.getCarrying()));
        }
        if (st instanceof CallStmt c) {
            Described d =
                    new Described(Kinds.CALL, "call " + c.getWorkflowName())
                            .attrs(
                                    Attrs.create()
                                            .positive(
                                                    "args",
                                                    c.getArguments() == null
                                                            ? 0
                                                            : c.getArguments().size())
                                            .text("variable", c.getResultVariable()));
            d.call = new CallLink(c.getWorkflowName(), null);
            return d;
        }
        if (st instanceof GuardrailStmt g) {
            return new Described(Kinds.GUARDRAIL, "guardrail " + g.getType())
                    .attrs(Attrs.create().text("type", g.getType()));
        }
        if (st instanceof DecideStmt d) {
            return new Described(Kinds.DECIDE, "decide " + d.getDecision())
                    .attrs(
                            Attrs.create()
                                    .text("decision", d.getDecision())
                                    .text("variable", d.getVariable())
                                    .text("level", decisionLevels.get(d.getDecision())));
        }
        if (st instanceof ObserveStmt o) {
            return new Described(Kinds.OBSERVE, "observe " + o.getLabel())
                    .attrs(Attrs.create().text("text", o.getExpression()));
        }
        if (st instanceof io.github.llm4j.loom.ast.StartStmt x) {
            return new Described(Kinds.CHILD_START, "start " + x.getWorkflow())
                    .attrs(Attrs.create().text("workflow", x.getWorkflow()).text("handle", x.getTarget().describe()));
        }
        if (st instanceof io.github.llm4j.loom.ast.WaitStmt x) {
            return new Described(Kinds.CHILD_WAIT, "wait " + (x.isAll() ? "all " : "") + x.getTarget().describe())
                    .attrs(Attrs.create().text("handle", x.getTarget().describe()));
        }
        if (st instanceof io.github.llm4j.loom.ast.CancelStmt x) {
            return new Described(Kinds.CHILD_CANCEL, "cancel " + x.getTarget().describe())
                    .attrs(Attrs.create().text("handle", x.getTarget().describe()));
        }
        if (st instanceof io.github.llm4j.loom.ast.SteerStmt x) {
            return new Described(Kinds.CHILD_WATCH, "steer " + x.getTarget().describe())
                    .attrs(Attrs.create().text("handle", x.getTarget().describe()).text("what", "steer " + x.getAgent()));
        }
        if (st instanceof io.github.llm4j.loom.ast.ReportStmt x) {
            return new Described(Kinds.CHILD_REPORT, "report " + String.join(", ", x.getValue().fields().keySet()))
                    .attrs(Attrs.create().text("fields", String.join(", ", x.getValue().fields().keySet())));
        }
        if (st instanceof io.github.llm4j.loom.ast.SuperviseStmt x) {
            return new Described(Kinds.CHILD_WATCH, "supervise " + x.getTarget().describe())
                    .attrs(Attrs.create().text("handle", x.getTarget().describe()).text("what", "supervise"));
        }
        if (st instanceof SleepStmt z) {
            return new Described(Kinds.SLEEP, "sleep " + z.getText())
                    .attrs(Attrs.create().text("duration", z.getText()));
        }
        if (st instanceof NoteStmt n) {
            return new Described(Kinds.NOTE, "note")
                    .attrs(Attrs.create().text("text", n.getMessage()));
        }
        if (st instanceof io.github.llm4j.loom.ast.HaltStmt h) {
            return new Described(Kinds.HALT, "halt")
                    .attrs(Attrs.create().text("text", h.getMessage()));
        }
        if (st instanceof io.github.llm4j.loom.ast.SetStmt s) {
            return new Described(Kinds.SET, "set " + s.getName())
                    .attrs(Attrs.create().text("value", valueText(s.getValue())));
        }
        if (st instanceof io.github.llm4j.loom.ast.ReturnStmt) {
            return new Described(Kinds.RETURN, "return");
        }
        return new Described(
                Kinds.UNKNOWN,
                st.getClass()
                        .getSimpleName()
                        .replaceAll("(Stmt|Statement)$", "")
                        .toLowerCase(Locale.ROOT));
    }

    /** The kinds a step attaches, in order: the file names are script text and are not shown. */
    private static List<String> kinds(List<io.github.llm4j.loom.ast.AttachItem> items) {
        List<String> out = new ArrayList<>();
        for (io.github.llm4j.loom.ast.AttachItem item : items) out.add(item.kind().label());
        return out;
    }

    private Described delegate(DelegateStmt d) {
        return new Described(Kinds.DELEGATE, "delegate " + d.getTargetAgent())
                .agent(d.getTargetAgent())
                .attrs(
                        Attrs.create()
                                .text("variable", d.getVariableName())
                                .list("attaches", kinds(d.getAttachments()))
                                .positive("retry", d.getRetryCount())
                                .positive("backoffMs", d.getBackoffMillis())
                                .positive("timeoutMs", d.getTimeoutMillis())
                                .text("expecting", SchemaOutline.of(d.getExpecting()))
                                .budget(d.getBudget())
                                .text("text", d.getPayload()));
    }

    /** A call to an agent someone else runs: opaque, untrusted, outside the budgets, and possibly slow. */
    private Described remote(DelegateStmt d, GraphBuilder.RemoteInfo info) {
        return new Described(Kinds.REMOTE, "remote " + d.getTargetAgent())
                .attrs(
                        Attrs.create()
                                .text("remote", d.getTargetAgent())
                                .text("host", info.host())
                                .text("channel", info.channel())
                                .text("audience", a2a.audiences().get(info.channel()))
                                .text("variable", d.getVariableName())
                                .list("attaches", kinds(d.getAttachments()))
                                .positive("retry", d.getRetryCount())
                                .positive("backoffMs", d.getBackoffMillis())
                                .positive("timeoutMs", d.getTimeoutMillis())
                                .text("expecting", SchemaOutline.of(d.getExpecting()))
                                .flag("outsideBudgets", true));
    }

    /** A question to a declared channel. The question text is not shown: it can carry variables. */
    private Described ask(io.github.llm4j.loom.ast.AskStmt a) {
        return new Described(Kinds.ASK, "ask " + a.getChannel())
                .attrs(
                        Attrs.create()
                                .text("channel", a.getChannel())
                                .text("audience", a2a.audiences().get(a.getChannel()))
                                .text("variable", a.getVariable())
                                .text("expecting", SchemaOutline.of(a.getExpecting()))
                                .positive("retry", a.getRetryCount())
                                .positive("backoffMs", a.getBackoffMillis())
                                .positive("timeoutMs", a.getTimeoutMillis()));
    }

    private Described run(RunStmt r) {
        return new Described(Kinds.TASK, "run " + r.getTaskName())
                .attrs(
                        Attrs.create()
                                .text("task", r.getTaskName())
                                .text("variable", r.getVariableName())
                                .text("model", r.getModel())
                                .positive("args", r.getArgs().size())
                                .positive("retry", r.getRetryCount())
                                .positive("backoffMs", r.getBackoffMillis())
                                .positive("timeoutMs", r.getTimeoutMillis()));
    }

    private Described use(io.github.llm4j.loom.ast.UseStmt u) {
        List<String> args = new ArrayList<>();
        for (RunStmt.Arg a : u.getArgs()) args.add(a.name());
        return new Described(Kinds.USE, "use " + u.getTool())
                .attrs(
                        Attrs.create()
                                .text("tool", u.getTool())
                                .text("variable", u.getVariableName())
                                .list("args", args)
                                .flag("approve", u.isApprove())
                                .positive("retry", u.getRetryCount())
                                .positive("backoffMs", u.getBackoffMillis())
                                .positive("timeoutMs", u.getTimeoutMillis()));
    }

    private Described check(io.github.llm4j.loom.ast.CheckStmt k) {
        return new Described(Kinds.CHECK, "check " + k.getSubject())
                .attrs(
                        Attrs.create()
                                .text("subject", k.getSubject())
                                .list("policies", k.getPolicies())
                                .text("evaluator", k.getEvaluator())
                                .text("onViolation", actionWord(k.getOnViolation()))
                                .text("onUnavailable", actionWord(k.getOnUnavailable()))
                                .text("variable", k.getVerdictVariable())
                                .positive("retry", k.getRetryCount())
                                .positive("timeoutMs", k.getTimeoutMillis()));
    }

    private static String actionWord(io.github.llm4j.loom.ast.CheckStmt.Action action) {
        if (action instanceof io.github.llm4j.loom.ast.CheckStmt.Rewrite r)
            return "rewrite with " + r.agent();
        if (action instanceof io.github.llm4j.loom.ast.CheckStmt.Replace) return "replace";
        if (action instanceof io.github.llm4j.loom.ast.CheckStmt.AskPerson) return "ask person";
        return "fail";
    }

    private Described extract(io.github.llm4j.loom.ast.ExtractStmt x) {
        List<String> inputs = new ArrayList<>();
        for (RunStmt.Arg in : x.getInputs()) inputs.add(in.name().isEmpty() ? in.text() : in.name());
        return new Described(Kinds.EXTRACT, "extract " + x.getExtractor())
                .attrs(
                        Attrs.create()
                                .text("extractor", x.getExtractor())
                                .text("variable", x.getVariableName())
                                .list("inputs", inputs)
                                .list(
                                        "attaches",
                                        kinds(
                                                x.getMediaInputs().stream()
                                                        .map(io.github.llm4j.loom.ast.ExtractMedia::item)
                                                        .toList()))
                                .positive("retry", x.getRetryCount())
                                .positive("backoffMs", x.getBackoffMillis())
                                .positive("timeoutMs", x.getTimeoutMillis()));
    }

    private Described classify(io.github.llm4j.loom.ast.IntentClassifyStmt c) {
        Attrs attrs =
                Attrs.create()
                        .text("classifier", c.getClassifier())
                        .text("variable", c.getVariableName());
        GraphBuilder.ClassifierInfo info = classifiers.get(c.getClassifier());
        if (info != null) {
            io.github.llm4j.loom.ast.IntentClassifierDef def = info.def();
            List<String> labels = new ArrayList<>();
            for (io.github.llm4j.loom.ast.IntentClassifierDef.Question q : def.getQuestions()) {
                if (q.kind() != io.github.llm4j.loom.ast.IntentClassifierDef.Kind.ASK)
                    labels.add(q.name() + ": " + String.join(" | ", q.labels()));
            }
            io.github.llm4j.loom.ast.IntentClassifierDef.Confidence rule = def.getConfidence();
            attrs.text("model", def.getModel())
                    .text("backend", !info.jev() ? "chat" : info.perplexity() ? "decisions" : "jev")
                    .flag("calibrated", info.jev())
                    .flag("uncalibrated", !info.jev())
                    .positive("questions", def.getQuestions().size())
                    .positive("samples", def.getSamples() > 1 ? def.getSamples() : 0)
                    .list("labels", labels)
                    .text(
                            "confidence",
                            rule == null
                                    ? null
                                    : "at least "
                                            + rule.threshold()
                                            + " else "
                                            + switch (rule.action()) {
                                                case ESCALATE -> "escalate";
                                                case ASK_PERSON -> "ask person";
                                                case FAIL -> "fail";
                                            });
        }
        attrs.positive("retry", c.getRetryCount())
                .positive("backoffMs", c.getBackoffMillis())
                .positive("timeoutMs", c.getTimeoutMillis());
        return new Described(Kinds.CLASSIFY, "classify " + c.getClassifier()).attrs(attrs);
    }

    private Described parallel(ParallelStmt p) {
        Set<String> agents = new LinkedHashSet<>();
        for (Statement child : p.getBody()) {
            if (child instanceof DelegateStmt d) {
                agents.add(d.getTargetAgent());
            }
        }
        return new Described(Kinds.PARALLEL, "in parallel: " + String.join(", ", agents))
                .attrs(
                        Attrs.create()
                                .list("agents", new ArrayList<>(agents))
                                .positive("branches", p.getBody().size()));
    }

    /** A value as the script wrote it, short: an object shows only its field names. */
    private static String valueText(io.github.llm4j.loom.ast.ValueExpr value) {
        if (value == null) {
            return null;
        }
        if (value instanceof io.github.llm4j.loom.ast.ValueExpr.Text t) return "\"" + t.text() + "\"";
        if (value instanceof io.github.llm4j.loom.ast.ValueExpr.Num n) return n.literal();
        if (value instanceof io.github.llm4j.loom.ast.ValueExpr.Bool b) return String.valueOf(b.value());
        if (value instanceof io.github.llm4j.loom.ast.ValueExpr.Ref r) return r.path();
        io.github.llm4j.loom.ast.ValueExpr.Obj o = (io.github.llm4j.loom.ast.ValueExpr.Obj) value;
        return "{ " + String.join(", ", o.fields().keySet()) + " }";
    }

    private static String cut(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > LABEL_LIMIT ? text.substring(0, LABEL_LIMIT) + "…" : text;
    }

    /** What is known about a statement before it is given an id and a place. */
    private static final class Described {
        final String kind;
        final String label;
        String agent;
        Integer bound;
        CallLink call;
        Map<String, Object> attrs = Map.of();

        Described(String kind, String label) {
            this.kind = kind;
            this.label = label;
        }

        Described agent(String name) {
            this.agent = name;
            return this;
        }

        Described attrs(Attrs built) {
            this.attrs = built.build();
            return this;
        }
    }
}
