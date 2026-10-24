package io.github.llm4j.loom.debug;

import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.execution.AskHook;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.VariableView;
import io.github.llm4j.loom.explain.Why;
import io.github.llm4j.loom.graph.GraphNode;
import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.GraphService;
import io.github.llm4j.loom.graph.WorkflowGraph;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.ObserveReplay;
import io.github.llm4j.loom.travel.ObserveStop;
import io.github.llm4j.loom.travel.StepView;
import io.github.llm4j.loom.whatif.WhatIf;
import io.github.llm4j.loom.wtest.StepResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.SynchronousQueue;
import java.util.function.Function;

/**
 * Runs a workflow one statement at a time. The workflow is run by the same interpreter as any run
 * (over a copy of a journal, with nothing that has an effect), and the stepper is the gate before
 * each statement: it waits there until told to go on. Going <em>back</em> is not undoing anything:
 * the run is replayed from its journal, cut back to the statement before, so the state there is
 * exactly the one that was, and the original journal is never changed.
 *
 * <p>Models are mock (or live, under a cap, when asked), classifiers answer their first label, and
 * tools that change things are only described; any answer can be scripted with {@link #model} and
 * {@link #classifier}, and a person's answer is given with {@link #answer}. What is scripted for a
 * statement is kept, so going back and on again gives the same run.
 */
public final class Stepper implements AutoCloseable {

    /** How far to go before stopping again. */
    public enum Move {
        /** The very next statement, even inside a branch, a loop body or a call. */
        INTO,
        /** The next statement at this level: the whole of this one runs. */
        OVER,
        /** The next statement after the block this one is in. */
        OUT,
        /** The next breakpoint, or the end. */
        CONTINUE
    }

    /**
     * @param script the script to step through
     * @param workflow the workflow to run
     * @param inputs its inputs
     * @param masked variable names never shown
     * @param start the journal to start from: an empty one for a new run, or a run's own to step
     *     through what it did (and on from where it stopped)
     * @param live the models to call instead of the mock ones, under a cap, or null
     */
    public record Config(
            Path script,
            String workflow,
            Map<String, String> inputs,
            Set<String> masked,
            RunJournal start,
            ObserveReplay.Live live) {}

    /**
     * @param index how many statements ran before this one
     * @param kind what kind of statement it is, as the graph names it ({@code alt}, {@code
     *     delegate})
     * @param statement the statement as the graph labels it
     * @param enclosing the statements this one is inside, outermost first
     * @param after the statements that follow in the same block
     */
    public record Position(
            int index,
            String step,
            String workflow,
            String node,
            String kind,
            int line,
            String statement,
            List<String> enclosing,
            List<String> after) {}

    /**
     * Where the run is.
     *
     * @param finished the run ended (see {@code status}); otherwise it is paused before {@code
     *     position}
     * @param hit why it stopped here: {@code start}, {@code step}, {@code breakpoint N}, {@code
     *     end}
     * @param status for a finished run: completed, failed, suspended, halted, stopped, needs or
     *     budget
     * @param reason what a suspended run waits for, or why a failed one failed
     * @param result what the workflow returned, if it has
     */
    public record State(
            boolean finished,
            Position position,
            List<VariableView> variables,
            String hit,
            String status,
            String reason,
            Object result,
            long calls,
            long tokens) {}

    // ---- the run on its own thread

    private record Pause(State state) {}

    private record Ended(State state, Throwable error) {}

    private enum Do {
        RESUME,
        EDIT,
        EVAL,
        TEST,
        ABORT
    }

    private record Command(Do what, Move move, String name, Object value) {}

    private record Reply(Object value, State state, String problem) {}

    private final Config config;
    private final LoomScript script;
    private final GraphResult graph;
    private final StepResolver resolver;
    private final Function<String, String> labels;
    private final Map<String, GraphNode> nodes = new HashMap<>();
    private final Breakpoints breakpoints = new Breakpoints();

    /** Everything the run has written so far (the replay's journal at the last pause). */
    private Map<String, RunJournal.Entry> journal;

    /** The edits made while stepping, by {@code step#set}; they outlive going back. */
    private final Map<String, RunJournal.Entry> edits = new LinkedHashMap<>();

    private final Map<String, Deque<Object>> scripts = new HashMap<>();
    private final Map<String, Object> supplied = new HashMap<>();
    private final Map<String, String> suppliedFor = new HashMap<>();

    private Thread worker;
    private SynchronousQueue<Object> events;
    private SynchronousQueue<Command> commands;
    private SynchronousQueue<Reply> replies;
    private State state;
    private final List<String> visited = new ArrayList<>();

    private Stepper(Config config) {
        this.config = config;
        try {
            this.script = new LoomLoader().load(config.script().toString());
            this.graph = new GraphService().graph(config.script());
            this.labels = Why.labels(config.script());
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        this.resolver = new StepResolver(graph);
        for (WorkflowGraph w : graph.workflows())
            for (GraphNode n : w.nodes()) nodes.put(w.name() + "/" + n.id(), n);
        this.journal = new LinkedHashMap<>(config.start().all());
    }

    /** Opens a stepper, paused before the first statement (or finished, if there is none). */
    public static Stepper open(Config config) {
        Stepper s = new Stepper(config);
        s.begin(0, Move.INTO, null);
        return s;
    }

    // ---- looking

    public synchronized State state() {
        return state;
    }

    public Breakpoints breakpoints() {
        return breakpoints;
    }

    /** What the run has written so far, including edits made while stepping. */
    public synchronized Map<String, RunJournal.Entry> journal() {
        Map<String, RunJournal.Entry> all = new LinkedHashMap<>(journal);
        all.putAll(edits);
        return all;
    }

    /** The statements that have run (not the one the run is paused before). */
    public synchronized List<String> ranSteps() {
        int n = state.finished() ? visited.size() : state.position().index();
        return List.copyOf(visited.subList(0, Math.min(n, visited.size())));
    }

    // ---- moving

    public synchronized State move(Move move) {
        requirePaused();
        Position at = state.position();
        String anchor =
                switch (move) {
                    case OVER -> at.step();
                    case OUT -> parentOf(at.step());
                    default -> null;
                };
        if (move == Move.OUT && anchor == null) move = Move.CONTINUE;
        return resume(move, anchor);
    }

    /** One statement back: the run is replayed to the one before, and waits there. */
    public synchronized State back() {
        int now = state.finished() ? visited.size() : state.position().index();
        if (now == 0)
            throw new IllegalStateException(
                    "This is the first statement; there is nothing before it.");
        int target = now - 1;
        String step = visited.get(target);
        journal = cutBack(journal, visited, target);
        closeSession();
        return begin(target, Move.INTO, step);
    }

    // ---- changing

    /** Sets a variable before the statement; going back and on again keeps the edit. */
    public synchronized State set(String name, String value) {
        requirePaused();
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("\"" + name + "\" is not a variable name.");
        String step = state.position().step();
        Map<String, Object> sets = new LinkedHashMap<>();
        RunJournal.Entry before = edits.get(step + ObserveReplay.SET);
        if (before != null && before.value() instanceof Map<?, ?> m)
            m.forEach((k, v) -> sets.put(String.valueOf(k), v));
        sets.put(name, value);
        edits.put(step + ObserveReplay.SET, new RunJournal.Entry("whatif_set", sets));
        Reply r = send(new Command(Do.EDIT, null, name, value));
        state = r.state();
        return state;
    }

    /** What the next call of an agent says. */
    public synchronized void model(String agent, String reply) {
        if (script.getAgents().stream().noneMatch(a -> a.getName().equals(agent)))
            throw new IllegalArgumentException(agent + " is not an agent of this script.");
        script(agent, answerValue(reply));
    }

    /**
     * A reply that is a JSON object or list is a typed answer (for an agent with an output schema).
     */
    private static Object answerValue(String text) {
        String t = text.strip();
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(t, Object.class);
            } catch (java.io.IOException e) {
                // not JSON: it is the text of the answer
            }
        }
        return text;
    }

    /** What the next call of a classifier says: {@code {"question": "label", ...}}. */
    public synchronized void classifier(String name, String json) {
        IntentClassifierDef def = script.findIntentClassifier(name);
        if (def == null)
            throw new IllegalArgumentException(name + " is not a classifier of this script.");
        script(name, WhatIf.classifierRecord(def, json));
    }

    private void script(String target, Object answer) {
        scripts.computeIfAbsent(target, k -> new ArrayDeque<>()).add(answer);
        // an answer already chosen for a statement that has not run yet gives way to this one
        suppliedFor
                .entrySet()
                .removeIf(
                        e ->
                                e.getValue().equals(target) && supplied.remove(e.getKey()) != null
                                        || e.getValue().equals(target));
    }

    /** A person's answer for the question the run waits on; the run is then paused before it. */
    public synchronized State answer(String text) {
        if (!state.finished() || !"suspended".equals(state.status()))
            throw new IllegalStateException("Nothing is waiting for an answer.");
        String step = visited.get(visited.size() - 1);
        journal.put(step, new RunJournal.Entry("human", text));
        int target = visited.size() - 1;
        closeSession();
        return begin(target, Move.INTO, step);
    }

    /** The value of a variable path now. */
    public synchronized Object value(String path) {
        requirePaused();
        return send(new Command(Do.EVAL, null, path, null)).value();
    }

    /** Whether a condition holds now, over the variables as they are. */
    public synchronized boolean holds(String condition) {
        requirePaused();
        String problem = Breakpoints.problemWith(condition);
        if (problem != null) throw new IllegalArgumentException(problem);
        return Boolean.TRUE.equals(send(new Command(Do.TEST, null, condition, null)).value());
    }

    // ---- breakpoints

    /**
     * Adds a breakpoint from what a person typed: a line number, a node ({@code n3}), an agent or
     * classifier name, or {@code when <condition>}.
     */
    public Breakpoints.Breakpoint breakAt(String spec) {
        String s = spec.strip();
        // "15 when reply == \"x\"": a place, and a condition that must hold there too
        java.util.regex.Matcher both =
                java.util.regex.Pattern.compile("(\\S+)\\s+when\\s+(.*)").matcher(s);
        if (both.matches() && !both.group(1).equals("when")) {
            String problem = Breakpoints.problemWith(both.group(2));
            if (problem != null) throw new IllegalArgumentException(problem);
            Breakpoints.Breakpoint base = breakAt(both.group(1));
            breakpoints.remove(base.id());
            return breakpoints.add(base.kind(), base.target(), base.line(), both.group(2).strip());
        }
        if (s.equals("when") || s.startsWith("when ")) {
            String condition = s.substring(4).strip();
            String problem = Breakpoints.problemWith(condition);
            if (problem != null) throw new IllegalArgumentException(problem);
            return breakpoints.add("when", condition, null);
        }
        if (s.matches("\\d+")) {
            int wanted = Integer.parseInt(s);
            int line = nearestLine(wanted);
            if (line < 0)
                throw new IllegalArgumentException(
                        "There is no statement at or after line " + wanted + ".");
            return breakpoints.add("line", s, line);
        }
        if (s.matches("n\\d+")) {
            if (nodes.keySet().stream().noneMatch(k -> k.endsWith("/" + s)))
                throw new IllegalArgumentException("There is no node " + s + " in the graph.");
            return breakpoints.add("node", s, null);
        }
        boolean agent =
                script.getAgents().stream().anyMatch(a -> a.getName().equals(s))
                        || script.findIntentClassifier(s) != null;
        if (!agent)
            throw new IllegalArgumentException(
                    "\""
                            + s
                            + "\" is not a line, a node (n3), an agent, a classifier or \"when <condition>\".");
        return breakpoints.add("agent", s, null);
    }

    private int nearestLine(int wanted) {
        int best = -1;
        for (GraphNode n : nodes.values()) {
            if (n.source() == null || n.source().line() < wanted) continue;
            if (best < 0 || n.source().line() < best) best = n.source().line();
        }
        return best;
    }

    // ---- the thread

    private void requirePaused() {
        if (state.finished())
            throw new IllegalStateException(
                    "The run has ended ("
                            + state.status()
                            + "). Go back to a statement with back"
                            + ("suspended".equals(state.status())
                                    ? ", or give the answer it waits for"
                                    : "")
                            + ".");
    }

    private State resume(Move move, String anchor) {
        Reply r = send(new Command(Do.RESUME, move, anchor, null));
        return state = r.state();
    }

    /** Starts a run and lets it go on until it pauses before statement {@code target}. */
    private State begin(int target, Move move, String expectStep) {
        events = new SynchronousQueue<>();
        commands = new SynchronousQueue<>();
        replies = new SynchronousQueue<>();
        visited.clear();
        Map<String, RunJournal.Entry> start = new LinkedHashMap<>(journal);
        start.putAll(edits);
        RunJournal input = RunJournalOf.of(start);
        worker = new Thread(() -> work(input, target, move), "loom-stepper");
        worker.setDaemon(true);
        worker.start();
        state = await();
        return state;
    }

    private State await() {
        try {
            Object e = events.take();
            if (e instanceof Pause p) return p.state();
            Ended end = (Ended) e;
            if (end.error() != null)
                throw new IllegalStateException(
                        "The stepper failed: " + end.error().getMessage(), end.error());
            return end.state();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }

    private Reply send(Command c) {
        try {
            commands.put(c);
            if (c.what() == Do.RESUME) {
                State next = await();
                return new Reply(null, next, null);
            }
            Reply r = replies.take();
            if (r.problem() != null) throw new IllegalArgumentException(r.problem());
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }

    private void closeSession() {
        Thread t = worker;
        worker = null;
        if (t == null) return;
        if (t.isAlive()) {
            commands.offer(new Command(Do.ABORT, null, null, null));
            t.interrupt();
        }
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void close() {
        closeSession();
    }

    // ---- on the worker

    private void work(RunJournal input, int target, Move firstMove) {
        // the mode the gate is in; changed by each resume command
        Object[] mode = {firstMove, null}; // move, anchor step
        int[] counter = {0};
        AskHook ask = this::answerFor;
        ObserveReplay.Live live =
                config.live() != null
                        ? config.live()
                        : new ObserveReplay.Live(new MockModels(), null, null, null, k -> null);
        try {
            ObserveReplay.Result result =
                    ObserveReplay.run(
                            new ObserveReplay.Options(
                                    config.script(),
                                    config.workflow(),
                                    config.inputs(),
                                    config.masked()),
                            input,
                            new ObserveReplay.Hooks(
                                    ask,
                                    null,
                                    null,
                                    null,
                                    null,
                                    live,
                                    view -> gate(view, target, counter, mode)));
            journal = new LinkedHashMap<>(result.journal());
            events.put(new Ended(finished(result), null));
        } catch (ObserveStop | InterruptedException stop) {
            // closed from outside
        } catch (Throwable t) {
            try {
                events.put(new Ended(null, t));
            } catch (InterruptedException ignored) {
                // closed
            }
        }
    }

    private Object answerFor(io.github.llm4j.loom.execution.Ask ask, boolean recorded) {
        if (recorded) return null;
        Object kept = supplied.get(ask.step());
        if (kept != null) return kept;
        Deque<Object> queue = scripts.get(ask.target());
        Object answer = queue == null ? null : queue.poll();
        if (answer == null && "classify".equals(ask.kind())) {
            IntentClassifierDef def = script.findIntentClassifier(ask.target());
            if (def != null) {
                Map<String, Object> record = WhatIf.classifierRecord(def, firstLabels(def));
                record.put("backend", "stepper: first label");
                answer = record;
            }
        }
        if (answer != null) {
            supplied.put(ask.step(), answer);
            suppliedFor.put(ask.step(), ask.target());
        }
        return answer;
    }

    private static String firstLabels(IntentClassifierDef def) {
        StringBuilder json = new StringBuilder("{");
        for (IntentClassifierDef.Question q : def.getQuestions()) {
            if (json.length() > 1) json.append(',');
            String label =
                    q.kind() == IntentClassifierDef.Kind.ASK ? "no" : def.possibleLabels(q).get(0);
            json.append('"').append(q.name()).append("\":\"").append(label).append('"');
        }
        return json.append('}').toString();
    }

    private void gate(StepView view, int target, int[] counter, Object[] mode) {
        int index = counter[0]++;
        visited.add(view.step());
        if (index < target) return;
        String hit = null;
        if (index == target) hit = target == 0 && mode[1] == null ? "start" : "step";
        else {
            Move move = (Move) mode[0];
            String anchor = (String) mode[1];
            switch (move) {
                case INTO -> hit = "step";
                case OVER, OUT -> {
                    if (!within(view.step(), anchor)) hit = "step";
                }
                default -> {}
            }
        }
        String broke = breakpointHit(view);
        if (broke != null) hit = broke;
        if (hit == null) return;
        journal = new LinkedHashMap<>(view.journal().get());
        State here = snapshot(view, index, hit);
        try {
            events.put(new Pause(here));
            while (true) {
                Command c = commands.take();
                switch (c.what()) {
                    case ABORT -> throw new ObserveStop("closed");
                    case EVAL -> replies.put(new Reply(view.resolve().apply(c.name()), null, null));
                    case TEST -> replies.put(new Reply(view.test().test(c.name()), null, null));
                    case EDIT -> {
                        view.bind().accept(c.name(), c.value());
                        replies.put(new Reply(null, snapshot(view, index, "step"), null));
                    }
                    case RESUME -> {
                        mode[0] = c.move();
                        mode[1] = c.name();
                        return;
                    }
                }
            }
        } catch (InterruptedException e) {
            throw new ObserveStop("closed");
        }
    }

    /**
     * Whether {@code step} is the anchor or inside it (a branch, a loop round, a called workflow).
     */
    static boolean within(String step, String anchor) {
        if (anchor == null) return false;
        String a = anchor.replaceAll("~\\d+$", "");
        String s = step;
        return s.equals(anchor)
                || s.startsWith(a + "/")
                || s.startsWith(a + "~")
                || s.startsWith(a + ">");
    }

    /** The statement that encloses this one, or null at the top level of a workflow. */
    static String parentOf(String step) {
        String own = step.substring(step.lastIndexOf('>') + 1);
        String prefix = step.substring(0, step.length() - own.length());
        List<String> parts = new ArrayList<>(List.of(own.split("/")));
        if (parts.size() <= 2) return null;
        // drop the statement's own part (a0, b1, r1.0, ...) and, in a for each, the item (e2)
        parts.remove(parts.size() - 1);
        if (parts.size() > 2 && parts.get(parts.size() - 1).matches("e\\d+"))
            parts.remove(parts.size() - 1);
        return prefix + String.join("/", parts);
    }

    private String breakpointHit(StepView view) {
        GraphNode node = nodeOf(view.step());
        for (Breakpoints.Breakpoint b : breakpoints.all()) {
            boolean placed =
                    switch (b.kind()) {
                        case "line" -> node != null
                                && node.source() != null
                                && node.source().line() == b.line();
                        case "node" -> node != null && node.id().equals(b.target());
                        case "agent" -> node != null && b.target().equals(agentOf(node));
                        default -> {
                            try {
                                yield view.test().test(b.target());
                            } catch (RuntimeException e) {
                                yield false;
                            }
                        }
                    };
            boolean holds = true;
            if (placed && b.condition() != null && !b.kind().equals("when")) {
                try {
                    holds = view.test().test(b.condition());
                } catch (RuntimeException e) {
                    holds = false;
                }
            }
            if (placed && holds) return "breakpoint " + b.id() + " (" + b.describe() + ")";
        }
        return null;
    }

    private static String agentOf(GraphNode node) {
        if (node.agent() != null) return node.agent();
        String label = node.label();
        if (label.startsWith("intentclassify ")) return label.substring(15).split("\\s+")[0];
        return null;
    }

    private GraphNode nodeOf(String step) {
        Optional<String> key = resolver.resolve(step);
        return key.map(nodes::get).orElse(null);
    }

    private State snapshot(StepView view, int index, String hit) {
        GraphNode node = nodeOf(view.step());
        Optional<String> key = resolver.resolve(view.step());
        String workflow =
                key.map(k -> k.substring(0, k.indexOf('/'))).orElse(view.step().split("/")[0]);
        List<String> enclosing = new ArrayList<>();
        String parent = parentOf(view.step());
        while (parent != null) {
            enclosing.add(0, describe(parent));
            parent = parentOf(parent);
        }
        List<String> after = new ArrayList<>();
        if (node != null) {
            WorkflowGraph w =
                    graph.workflows().stream()
                            .filter(x -> x.name().equals(workflow))
                            .findFirst()
                            .orElse(null);
            if (w != null) {
                boolean seen = false;
                for (GraphNode n : w.nodes()) {
                    if (n.id().equals(node.id())) {
                        seen = true;
                        continue;
                    }
                    if (seen
                            && java.util.Objects.equals(n.parent(), node.parent())
                            && java.util.Objects.equals(n.branch(), node.branch())
                            && n.source() != null
                            && after.size() < 3)
                        after.add("line " + n.source().line() + "  " + n.label());
                }
            }
        }
        return new State(
                false,
                new Position(
                        index,
                        view.step(),
                        workflow,
                        node == null ? null : node.id(),
                        node == null ? null : node.kind(),
                        node == null || node.source() == null ? 0 : node.source().line(),
                        labels.apply(view.step()),
                        enclosing,
                        after),
                view.variables().get(),
                hit,
                null,
                null,
                null,
                view.calls(),
                view.tokens());
    }

    private String describe(String step) {
        GraphNode n = nodeOf(step);
        String text = labels.apply(step);
        return n != null && n.source() != null ? "line " + n.source().line() + "  " + text : text;
    }

    private State finished(ObserveReplay.Result r) {
        return new State(
                true,
                null,
                List.of(),
                "end",
                r.status(),
                r.reason(),
                r.variables().get("result"),
                r.calls(),
                r.tokens());
    }

    /** The journal without the results of the statement at {@code target} and what came after. */
    private Map<String, RunJournal.Entry> cutBack(
            Map<String, RunJournal.Entry> journal, List<String> visited, int target) {
        RunJournal in = RunJournalOf.of(journal);
        Map<String, RunJournal.Entry> out =
                new LinkedHashMap<>(WhatIf.cutBack(in, visited, target).all());
        // edits made while stepping stay, wherever they were made
        out.putAll(edits);
        return out;
    }
}
