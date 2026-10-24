package io.github.llm4j.loom.explain;

import io.github.llm4j.loom.execution.DecisionPoint;
import io.github.llm4j.loom.graph.GraphNode;
import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.GraphService;
import io.github.llm4j.loom.graph.Kinds;
import io.github.llm4j.loom.graph.WorkflowGraph;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.ObserveReplay;
import io.github.llm4j.loom.wtest.StepResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Why a run went where it did, worked out from its journal and its script alone: the run is
 * replayed with nothing that has an effect ({@link ObserveReplay}), and each place it chose a way
 * is reported with the condition as written, the values it was made of and where each came from,
 * the way taken and the ways not taken. Nothing is guessed: where the journal runs out the report
 * says so.
 */
public final class Why {

    private Why() {}

    /** The JSON version of a report. */
    public static final int VERSION = 1;

    /** What to ask about: nothing (the whole run), a step id, a graph node, or a source line. */
    public record Selector(String step, String node, Integer line) {
        public static Selector none() {
            return new Selector(null, null, null);
        }

        public boolean any() {
            return step != null || node != null || line != null;
        }
    }

    /**
     * @param selector which statement to ask about, if any
     * @param all every step, not only the choices and the steps that did something
     * @param since only entries after this step
     */
    public record Request(Selector selector, boolean all, String since) {
        public static Request path() {
            return new Request(Selector.none(), false, null);
        }
    }

    /** One line of the explanation: a choice, or a step that did something. */
    public record Entry(
            int n,
            String step,
            String node,
            int generation,
            int line,
            String kind,
            String statement,
            String condition,
            List<Operand> operands,
            Boolean result,
            String taken,
            List<String> notTaken,
            Map<String, Object> detail,
            String summary) {}

    /** A value a condition looked at, with where it came from. */
    public record Operand(
            String expr,
            String value,
            int length,
            String origin,
            String boundStep,
            String boundNode,
            int boundLine,
            String boundBy) {}

    /** The answer to a question about one statement. */
    public record Answer(String question, String text, Entry because, List<String> wouldNeed) {}

    public record Report(
            int version,
            String workflow,
            String script,
            String status,
            String reason,
            String stoppedAt,
            List<Entry> entries,
            List<String> notes,
            Answer answer) {}

    private static final Set<String> SHOWN =
            Set.of(
                    Kinds.DELEGATE,
                    Kinds.PARALLEL,
                    Kinds.TASK,
                    Kinds.CLASSIFY,
                    Kinds.HANDOFF,
                    Kinds.BROADCAST,
                    Kinds.HUMAN_PROMPT,
                    Kinds.CALL,
                    Kinds.DECIDE,
                    Kinds.RETURN,
                    Kinds.HALT,
                    Kinds.SLEEP);

    private static final Set<String> CHOOSING =
            Set.of(
                    Kinds.ALT,
                    Kinds.LOOP,
                    Kinds.FOREACH,
                    Kinds.CLASSIFY,
                    Kinds.REWIND,
                    Kinds.DECIDE);

    public static Report explain(
            Path script,
            String workflow,
            Map<String, String> inputs,
            RunJournal journal,
            Request request,
            Set<String> masked)
            throws IOException {
        ObserveReplay.Result replay =
                ObserveReplay.run(
                        new ObserveReplay.Options(script, workflow, inputs, masked), journal);
        GraphResult graph = new GraphService().graph(script);
        StepResolver resolver = new StepResolver(graph);
        Map<String, Map<String, GraphNode>> nodes = new LinkedHashMap<>();
        for (WorkflowGraph w : graph.workflows()) {
            Map<String, GraphNode> byId = new LinkedHashMap<>();
            w.nodes().forEach(n -> byId.put(n.id(), n));
            nodes.put(w.name(), byId);
        }

        Map<String, List<DecisionPoint>> decided = new LinkedHashMap<>();
        for (DecisionPoint d : replay.decisions())
            decided.computeIfAbsent(d.step(), k -> new ArrayList<>()).add(d);

        List<Entry> entries = new ArrayList<>();
        int sinceAt = request.since() == null ? -1 : replay.steps().indexOf(request.since());
        int startedSteps = -1;
        java.util.Set<String> choosingSteps = new java.util.HashSet<>();
        for (ObserveReplay.Event ev : replay.timeline()) {
            if (ev.decision() == null) {
                startedSteps++;
                if (startedSteps <= sinceAt) continue;
                GraphNode node = nodeOf(ev.step(), resolver, nodes);
                boolean choosing = node != null && CHOOSING.contains(node.kind());
                boolean decides =
                        replay.decisions().stream().anyMatch(d -> d.step().equals(ev.step()));
                if (choosing && decides) continue; // its choices are shown as they happen
                boolean shown = node != null && (request.all() || SHOWN.contains(node.kind()));
                if (shown) entries.add(stepEntry(entries.size() + 1, ev.step(), node, journal));
                if (node != null && node.kind().equals(Kinds.PARALLEL)) {
                    // the branches do not announce themselves: they are the journal's entries
                    // below this step, in order
                    for (int b = 0; journal.get(ev.step() + "/p" + b).isPresent(); b++)
                        entries.add(
                                stepEntry(entries.size() + 1, ev.step() + "/p" + b, node, journal));
                }
            } else {
                if (startedSteps <= sinceAt) continue;
                GraphNode node = nodeOf(ev.step(), resolver, nodes);
                entries.add(
                        entryOf(
                                entries.size() + 1,
                                ev.decision(),
                                node,
                                resolver,
                                nodes,
                                journal,
                                inputs));
            }
        }

        List<String> notes = new ArrayList<>();
        ObserveReplay.Divergence divergence = replay.divergence();
        if (divergence == null) divergence = mismatch(replay, journal, resolver, nodes);
        if (divergence != null)
            notes.add(
                    "Diverged at "
                            + divergence.step()
                            + ": "
                            + divergence.detail()
                            + ". Everything above is exact up to there.");
        switch (replay.status()) {
            case "suspended" -> notes.add(
                    "The run is waiting at " + replay.stoppedAt() + ": " + replay.reason());
            case "failed" -> notes.add(
                    "The run failed at " + replay.stoppedAt() + ": " + replay.reason());
            case "needs" -> notes.add(
                    "The journal ends at "
                            + replay.stoppedAt()
                            + ": the script would need "
                            + replay.reason()
                            + " there.");
            default -> {}
        }

        Answer answer = null;
        if (request.selector().any())
            answer = answer(request.selector(), replay, entries, graph, resolver, nodes);
        return new Report(
                VERSION,
                workflow,
                script.toString(),
                replay.status(),
                replay.reason(),
                replay.stoppedAt(),
                entries,
                notes,
                answer);
    }

    /**
     * A step whose journal entry is of a kind its statement could not have written: the script is
     * not the one that ran, whatever its fingerprint says.
     */
    private static ObserveReplay.Divergence mismatch(
            ObserveReplay.Result replay,
            RunJournal journal,
            StepResolver resolver,
            Map<String, Map<String, GraphNode>> nodes) {
        for (String step : replay.steps()) {
            var entry = journal.get(step);
            if (entry.isEmpty()) continue;
            GraphNode node = nodeOf(step, resolver, nodes);
            if (node == null) continue;
            String kind = entry.get().kind();
            if (!compatible(kind, node.kind()))
                return new ObserveReplay.Divergence(
                        step,
                        "mismatch",
                        "the journal holds a "
                                + kind
                                + " result there, but the script has "
                                + node.label());
        }
        return null;
    }

    private static boolean compatible(String entryKind, String nodeKind) {
        return switch (entryKind) {
            case "delegate", "citations", "audio" -> nodeKind.equals(Kinds.DELEGATE);
            case "classify" -> nodeKind.equals(Kinds.CLASSIFY);
            case "task" -> nodeKind.equals(Kinds.TASK);
            case "failed" -> nodeKind.equals(Kinds.DELEGATE)
                    || nodeKind.equals(Kinds.TASK)
                    || nodeKind.equals(Kinds.CLASSIFY);
            case "human" -> nodeKind.equals(Kinds.HUMAN_PROMPT) || nodeKind.equals(Kinds.DELEGATE);
            case "broadcast" -> nodeKind.equals(Kinds.BROADCAST);
            case "handoff" -> nodeKind.equals(Kinds.HANDOFF);
            case "sleep" -> nodeKind.equals(Kinds.SLEEP);
            case "halt" -> nodeKind.equals(Kinds.HALT);
            default -> true;
        };
    }

    // ---- entries

    private static GraphNode nodeOf(
            String step, StepResolver resolver, Map<String, Map<String, GraphNode>> nodes) {
        Optional<String> found = resolver.resolve(step);
        if (found.isEmpty()) return null;
        String[] parts = found.get().split("/", 2);
        Map<String, GraphNode> byId = nodes.get(parts[0]);
        return byId == null ? null : byId.get(parts[1]);
    }

    private static final Pattern GENERATION = Pattern.compile("~(\\d+)");

    static int generationOf(String step) {
        int g = 1;
        Matcher m = GENERATION.matcher(step);
        while (m.find()) g = Math.max(g, Integer.parseInt(m.group(1)));
        return g;
    }

    private static Entry entryOf(
            int n,
            DecisionPoint d,
            GraphNode node,
            StepResolver resolver,
            Map<String, Map<String, GraphNode>> nodes,
            RunJournal journal,
            Map<String, String> inputs) {
        List<Operand> operands = new ArrayList<>();
        for (DecisionPoint.Operand o : d.operands()) {
            GraphNode at = o.boundStep() == null ? null : nodeOf(o.boundStep(), resolver, nodes);
            String root =
                    o.expr().contains(".")
                            ? o.expr().substring(0, o.expr().indexOf('.'))
                            : o.expr();
            operands.add(
                    new Operand(
                            o.expr(),
                            o.value(),
                            o.length(),
                            o.boundStep() != null
                                    ? "bound"
                                    : inputs.containsKey(root) ? "input" : "unset",
                            o.boundStep(),
                            at == null ? null : at.id(),
                            o.boundLine(),
                            o.boundBy()));
        }
        return new Entry(
                n,
                d.step(),
                node == null ? null : node.id(),
                generationOf(d.step()),
                d.line() > 0
                        ? d.line()
                        : node != null && node.source() != null ? node.source().line() : 0,
                d.kind(),
                d.statement(),
                d.condition(),
                operands,
                d.result(),
                d.taken(),
                d.notTaken(),
                d.detail(),
                null);
    }

    private static final Pattern BRANCH = Pattern.compile(".*/p(\\d+)$");

    private static Entry stepEntry(int n, String step, GraphNode node, RunJournal journal) {
        Matcher branch = BRANCH.matcher(step);
        if (branch.matches() && node.kind().equals(Kinds.PARALLEL))
            return new Entry(
                    n,
                    step,
                    node.id(),
                    generationOf(step),
                    node.source() == null ? 0 : node.source().line(),
                    Kinds.DELEGATE,
                    "branch " + branch.group(1) + " of " + node.label(),
                    null,
                    List.of(),
                    null,
                    null,
                    List.of(),
                    Map.of(),
                    summary(journal.get(step).orElse(null)));
        return new Entry(
                n,
                step,
                node.id(),
                generationOf(step),
                node.source() == null ? 0 : node.source().line(),
                node.kind(),
                node.label(),
                null,
                List.of(),
                null,
                null,
                List.of(),
                Map.of(),
                journal.get(step).isPresent()
                        ? summary(journal.get(step).orElse(null))
                        : journal.get(step + "#failure")
                                .map(f -> "failed: " + shorten(failureMessage(f.value())))
                                .orElse(null));
    }

    private static String summary(RunJournal.Entry e) {
        if (e == null) return null;
        Object v = e.value();
        String text = String.valueOf(v);
        return switch (e.kind()) {
            case "delegate" -> "replied (" + text.length() + " chars)";
            case "failed" -> "failed: " + shorten(text);
            case "human" -> "answered " + quote(shorten(text));
            case "task" -> "returned " + shorten(text);
            case "broadcast" -> "replied (" + text.length() + " chars)";
            case "handoff" -> "handed off (" + text.length() + " chars)";
            case "halt" -> "halted: " + shorten(text);
            case "sleep" -> "slept until " + text;
            default -> e.kind();
        };
    }

    private static String failureMessage(Object value) {
        return value instanceof Map<?, ?> m && m.get("error") != null
                ? String.valueOf(m.get("error"))
                : String.valueOf(value);
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    private static String shorten(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() > 80 ? one.substring(0, 80) + "…" : one;
    }

    // ---- why this step

    /**
     * The step id of the first time the run executed the statement a selector names, if it ever
     * did.
     */
    public static Optional<String> locate(Path script, Selector sel, List<String> steps)
            throws IOException {
        if (sel.step() != null)
            return steps.contains(sel.step()) ? Optional.of(sel.step()) : Optional.empty();
        GraphResult graph = new GraphService().graph(script);
        StepResolver resolver = new StepResolver(graph);
        Map<String, Map<String, GraphNode>> nodes = new LinkedHashMap<>();
        for (WorkflowGraph w : graph.workflows()) {
            Map<String, GraphNode> byId = new LinkedHashMap<>();
            w.nodes().forEach(n -> byId.put(n.id(), n));
            nodes.put(w.name(), byId);
        }
        for (String step : steps) {
            GraphNode n = nodeOf(step, resolver, nodes);
            if (n == null) continue;
            boolean matches =
                    sel.node() != null
                            ? n.id().equals(sel.node())
                            : n.source() != null
                                    && sel.line() != null
                                    && n.source().line() == sel.line();
            if (matches) return Optional.of(step);
        }
        return Optional.empty();
    }

    /**
     * The label of the statement a step is ({@code delegate Replier}), or the step id when it
     * cannot be found.
     */
    public static java.util.function.Function<String, String> labels(Path script)
            throws IOException {
        GraphResult graph = new GraphService().graph(script);
        StepResolver resolver = new StepResolver(graph);
        Map<String, Map<String, GraphNode>> nodes = new LinkedHashMap<>();
        for (WorkflowGraph w : graph.workflows()) {
            Map<String, GraphNode> byId = new LinkedHashMap<>();
            w.nodes().forEach(n -> byId.put(n.id(), n));
            nodes.put(w.name(), byId);
        }
        return step -> {
            GraphNode n = nodeOf(step, resolver, nodes);
            return n == null ? step : n.label();
        };
    }

    private static Answer answer(
            Selector sel,
            ObserveReplay.Result replay,
            List<Entry> entries,
            GraphResult graph,
            StepResolver resolver,
            Map<String, Map<String, GraphNode>> nodes) {
        String question = "why " + describe(sel);
        // the steps that ran for the statement asked about
        List<String> ran = new ArrayList<>();
        GraphNode target = null;
        for (String step : replay.steps()) {
            GraphNode n = nodeOf(step, resolver, nodes);
            if (n == null) continue;
            boolean matches =
                    sel.step() != null
                            ? step.equals(sel.step())
                            : sel.node() != null
                                    ? n.id().equals(sel.node())
                                    : n.source() != null && n.source().line() == sel.line();
            if (matches) {
                ran.add(step);
                target = n;
            }
        }
        if (target == null) target = findNode(sel, graph);
        if (target == null)
            return new Answer(
                    question,
                    "There is no such step in this script: " + describe(sel) + ".",
                    null,
                    List.of());
        if (!ran.isEmpty()) {
            String step = ran.get(0);
            Entry because = enclosingDecision(step, entries);
            String text =
                    because == null
                            ? target.label()
                                    + " is a statement of its workflow's own list: it runs whenever the workflow reaches it"
                                    + (ran.size() > 1 ? " (" + ran.size() + " times)" : "")
                                    + "."
                            : target.label()
                                    + " ran because "
                                    + because.statement()
                                    + " "
                                    + verdict(because)
                                    + (ran.size() > 1 ? " (" + ran.size() + " times)" : "")
                                    + ".";
            return new Answer(question, text, because, List.of());
        }
        // it never ran: which choice kept it out
        GraphNode at = target;
        Map<String, GraphNode> byId = nodes.get(workflowOf(target, nodes));
        while (at != null) {
            if (at.parent() == null) break;
            GraphNode parent = byId.get(at.parent());
            if (parent == null) break;
            Entry decision = lastDecisionOf(parent, entries, resolver, nodes);
            if (decision != null && decision.taken() != null) {
                String branch = at.branch();
                boolean other = branch != null && !branch.equals(decision.taken());
                if (other || !"body".equals(branch)) {
                    List<String> need = new ArrayList<>();
                    String flip = flipped(decision);
                    if (flip != null) need.add(flip);
                    String text =
                            target.label()
                                    + " did not run: "
                                    + decision.statement()
                                    + " "
                                    + verdict(decision)
                                    + ", so the "
                                    + (branch == null ? "other" : branch)
                                    + " branch was not taken."
                                    + (flip == null ? "" : " It would have run if " + flip + ".");
                    return new Answer(question, text, decision, need);
                }
            }
            at = parent;
        }
        String where = replay.stoppedAt().isEmpty() ? "the start" : replay.stoppedAt();
        return new Answer(
                question,
                target.label()
                        + " did not run: the run "
                        + endedPhrase(replay)
                        + " at "
                        + where
                        + " before reaching it.",
                null,
                List.of());
    }

    private static String endedPhrase(ObserveReplay.Result r) {
        return switch (r.status()) {
            case "completed" -> "ended (it returned or ran out of statements)";
            case "suspended" -> "is waiting";
            case "failed" -> "failed";
            case "halted" -> "halted";
            case "stopped" -> "was stopped";
            default -> "stopped (the journal ends)";
        };
    }

    private static String describe(Selector s) {
        if (s.step() != null) return "step " + s.step();
        if (s.node() != null) return "node " + s.node();
        return "line " + s.line();
    }

    private static GraphNode findNode(Selector sel, GraphResult graph) {
        for (WorkflowGraph w : graph.workflows())
            for (GraphNode n : w.nodes()) {
                if (n.kind().equals(Kinds.START) || n.kind().equals(Kinds.END)) continue;
                if (sel.node() != null && n.id().equals(sel.node())) return n;
                if (sel.line() != null && n.source() != null && n.source().line() == sel.line())
                    return n;
            }
        return null;
    }

    private static String workflowOf(GraphNode n, Map<String, Map<String, GraphNode>> nodes) {
        for (var e : nodes.entrySet()) if (e.getValue().get(n.id()) == n) return e.getKey();
        return nodes.keySet().iterator().next();
    }

    /** The choice of the statement that holds {@code step} in a block (its parent). */
    private static Entry enclosingDecision(String step, List<Entry> entries) {
        String own = step;
        while (own.contains("/")) {
            own = own.substring(0, own.lastIndexOf('/'));
            String parent = own;
            Entry found = null;
            for (Entry e : entries) {
                if (e.step().equals(parent) && e.taken() != null) found = e;
                if (e.step().equals(step)) break;
            }
            if (found != null) return found;
        }
        return null;
    }

    private static Entry lastDecisionOf(
            GraphNode parent,
            List<Entry> entries,
            StepResolver resolver,
            Map<String, Map<String, GraphNode>> nodes) {
        Entry found = null;
        for (Entry e : entries) if (parent.id().equals(e.node()) && e.taken() != null) found = e;
        return found;
    }

    /** "... was false (intent.urgent was "no")". */
    static String verdict(Entry e) {
        StringBuilder sb = new StringBuilder();
        if (e.result() != null) sb.append("was ").append(e.result() ? "true" : "false");
        else sb.append("went ").append(e.taken());
        if (!e.operands().isEmpty()) {
            sb.append(" (");
            for (int i = 0; i < e.operands().size(); i++) {
                Operand o = e.operands().get(i);
                if (i > 0) sb.append(", ");
                sb.append(o.expr()).append(" was \"").append(o.value()).append("\"");
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private static final Pattern COMPARISON =
            Pattern.compile("^\\s*([A-Za-z_][\\w.]*)\\s*(==|!=)\\s*(\"[^\"]*\"|[0-9.]+)\\s*$");

    /** What would have had to hold for the other branch, for a plain comparison with a literal. */
    private static String flipped(Entry decision) {
        if (decision.condition() == null || decision.result() == null) return null;
        Matcher m = COMPARISON.matcher(decision.condition());
        if (!m.matches()) return null;
        boolean eq = m.group(2).equals("==");
        // the other branch needs the opposite truth value
        boolean needTrue = !decision.result();
        boolean needEqual = needTrue == eq;
        return m.group(1) + (needEqual ? " == " : " != ") + m.group(3);
    }
}
