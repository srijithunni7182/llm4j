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

    /**
     * @param decisionLevels decision name to the level it starts at ({@code watch}, {@code
     *     suggest}, {@code act})
     * @param classifiers classifier name to its declaration and backend
     */
    NodeDescriber(
            Map<String, String> decisionLevels,
            Map<String, GraphBuilder.ClassifierInfo> classifiers) {
        this.classifiers = classifiers;
        this.decisionLevels = decisionLevels;
    }

    GraphNode describe(String id, Statement statement, String file) {
        SourceRef source =
                statement.getLine() > 0 ? new SourceRef(file, statement.getLine()) : null;
        Described d = describe(statement);
        return new GraphNode(
                id, d.kind, d.label, d.agent, d.bound, source, d.call, false, null, null, d.attrs);
    }

    private Described describe(Statement st) {
        if (st instanceof DelegateStmt d) {
            return delegate(d);
        }
        if (st instanceof RunStmt r) {
            return run(r);
        }
        if (st instanceof io.github.llm4j.loom.ast.IntentClassifyStmt c) {
            return classify(c);
        }
        if (st instanceof HandoffStmt h) {
            return new Described(Kinds.HANDOFF, "handoff " + h.getTargetAgent())
                    .agent(h.getTargetAgent())
                    .attrs(Attrs.create().text("text", h.getPayload()));
        }
        if (st instanceof BroadcastStmt b) {
            return new Described(Kinds.BROADCAST, "broadcast")
                    .attrs(
                            Attrs.create()
                                    .list("agents", b.getTargetAgents())
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
            return new Described(
                            Kinds.FOREACH,
                            "for each " + f.getItemName() + " in " + f.getCollectionPath())
                    .attrs(
                            Attrs.create()
                                    .text("item", f.getItemName())
                                    .text("collection", f.getCollectionPath())
                                    .flag("parallel", f.isParallel())
                                    .budget(f.getBudget()));
        }
        if (st instanceof HumanPromptStmt h) {
            return new Described(Kinds.HUMAN_PROMPT, "ask a person")
                    .attrs(
                            Attrs.create()
                                    .text("variable", h.getVariableName())
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
            return new Described(Kinds.SET, "set " + s.getName());
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

    private Described delegate(DelegateStmt d) {
        return new Described(Kinds.DELEGATE, "delegate " + d.getTargetAgent())
                .agent(d.getTargetAgent())
                .attrs(
                        Attrs.create()
                                .text("variable", d.getVariableName())
                                .positive("retry", d.getRetryCount())
                                .positive("backoffMs", d.getBackoffMillis())
                                .positive("timeoutMs", d.getTimeoutMillis())
                                .text("expecting", SchemaOutline.of(d.getExpecting()))
                                .budget(d.getBudget())
                                .text("text", d.getPayload()));
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
