package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.IntentClassifyStmt;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.runtime.ConditionEvaluator;
import io.github.llm4j.loom.runtime.VariableContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a run tells a {@link DecisionListener}: where it chose a way, over which values, and where
 * each value came from. With no listener every method returns at once, so a run nobody watches does
 * no extra work and its trace and journal are exactly what they were.
 */
final class Decisions {

    private volatile DecisionListener listener;
    private volatile Set<String> masked = Set.of();

    /** What a thread is executing, so a variable set now is known to come from it. */
    private record Current(Statement statement, String step) {}

    private record Bound(String step, int line, String by) {}

    private final ThreadLocal<Current> current = new ThreadLocal<>();
    private final Map<String, Bound> bindings = new ConcurrentHashMap<>();

    private volatile AskHook askHook;

    boolean on() {
        return listener != null;
    }

    void askHook(AskHook hook) {
        this.askHook = hook;
    }

    boolean asking() {
        return askHook != null;
    }

    /** What the step asks; the hook's answer when it has one for a step with no recorded answer. */
    Object ask(Ask ask, boolean recorded) {
        AskHook hook = askHook;
        return hook == null ? null : hook.ask(ask, recorded);
    }

    void listen(DecisionListener listener, Set<String> maskedNames) {
        this.listener = listener;
        this.masked = maskedNames == null ? Set.of() : Set.copyOf(maskedNames);
    }

    /** A statement is about to run on this thread. */
    void enter(Statement statement, String step) {
        if (listener != null) current.set(new Current(statement, step));
    }

    /** A variable was set: remember by what. Names that begin with an underscore are the run's. */
    void bound(String name, Object value) {
        if (listener == null || name.startsWith("_")) return;
        Current c = current.get();
        if (c == null) return;
        bindings.put(
                name,
                new Bound(c.step(), c.statement().getLine(), StatementText.of(c.statement())));
    }

    /** A variable was set from outside the script ({@code by} says how); its provenance is that. */
    void rebind(String name, String step, String by) {
        if (listener != null) bindings.put(name, new Bound(step, 0, by));
    }

    // ---- conditions

    void alt(AltStmt alt, boolean result, VariableContext view, String step) {
        if (listener == null) return;
        List<String> notTaken = new ArrayList<>();
        var other = result ? alt.getElseBranch() : alt.getIfBranch();
        notTaken.add(
                (result ? "else" : "then")
                        + ": "
                        + (other == null || other.isEmpty()
                                ? "(nothing)"
                                : StatementText.of(other.get(0))));
        emit(
                new DecisionPoint(
                        step,
                        alt.getLine(),
                        "alt",
                        StatementText.of(alt),
                        ConditionEvaluator.display(alt.getCondition()),
                        operands(alt.getCondition(), view),
                        result,
                        result ? "then" : "else",
                        notTaken,
                        Map.of()));
    }

    /** One look at a loop's condition: {@code done} means leave. */
    void loopCheck(LoopStmt loop, boolean done, int round, VariableContext view, String step) {
        if (listener == null) return;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("done", round);
        if (loop.getMaxIterations() > 0) detail.put("max", loop.getMaxIterations());
        List<String> notTaken =
                List.of(
                        done
                                ? "body: "
                                        + (loop.getBody().isEmpty()
                                                ? "(nothing)"
                                                : StatementText.of(loop.getBody().get(0)))
                                : "exit");
        if (done) detail.put("exit", "condition");
        emit(
                new DecisionPoint(
                        step,
                        loop.getLine(),
                        "loop",
                        StatementText.of(loop),
                        ConditionEvaluator.display(loop.getCondition()),
                        operands(loop.getCondition(), view),
                        done,
                        done ? "exit" : "body",
                        notTaken,
                        detail));
    }

    /** A loop left without its condition holding: rounds ran out, or a budget refused. */
    void loopExhausted(LoopStmt loop, String by, int rounds, String step) {
        if (listener == null) return;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("exit", "rounds".equals(by) ? "max" : by);
        detail.put("rounds", rounds);
        if (loop.getMaxIterations() > 0) detail.put("max", loop.getMaxIterations());
        detail.put("on_exhausted", !loop.getOnExhausted().isEmpty());
        emit(
                new DecisionPoint(
                        step,
                        loop.getLine(),
                        "loop",
                        StatementText.of(loop),
                        ConditionEvaluator.display(loop.getCondition()),
                        List.of(),
                        false,
                        loop.getOnExhausted().isEmpty() ? "exit" : "on_exhausted",
                        List.of(),
                        detail));
    }

    void forEach(ForEachStmt each, int size, VariableContext view, String step) {
        if (listener == null) return;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("items", size);
        detail.put("item", each.getItemName());
        if (each.isParallel()) detail.put("parallel", true);
        emit(
                new DecisionPoint(
                        step,
                        each.getLine(),
                        "for each",
                        StatementText.of(each),
                        null,
                        operands(each.getCollectionPath(), view),
                        null,
                        size == 0 ? "skip" : "body x" + size,
                        List.of(),
                        detail));
    }

    /** A classifier answered: the answer for each question, and how sure it was. */
    void classified(IntentClassifyStmt stmt, String step, Map<String, Object> bound) {
        if (listener == null) return;
        Map<String, Object> detail = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : bound.entrySet())
            detail.put(e.getKey(), shown(e.getKey(), e.getValue()));
        emit(
                new DecisionPoint(
                        step,
                        stmt.getLine(),
                        "classify",
                        StatementText.of(stmt),
                        null,
                        List.of(),
                        null,
                        "answered",
                        List.of(),
                        detail));
    }

    /**
     * Something the run already says in its trace, told as a decision: a rewind, a failure taken
     * over.
     */
    void fromTrace(String type, String text, Map<String, Object> data, String step) {
        if (listener == null) return;
        String kind =
                switch (type) {
                    case TraceEvent.REWIND -> "rewind";
                    case TraceEvent.DECISION -> "decide";
                    case TraceEvent.GUARD -> "guard";
                    case TraceEvent.FAILURE -> data != null
                                    && Boolean.TRUE.equals(data.get("handled"))
                            ? "failure"
                            : null;
                    default -> null;
                };
        if (kind == null) return;
        Current c = current.get();
        Map<String, Object> detail = new LinkedHashMap<>();
        if (text != null) detail.put("text", text);
        if (data != null) detail.putAll(data);
        emit(
                new DecisionPoint(
                        step,
                        c == null ? 0 : c.statement().getLine(),
                        kind,
                        c == null ? kind : StatementText.of(c.statement()),
                        null,
                        List.of(),
                        null,
                        kind.equals("failure") ? "on_failure" : kind,
                        List.of(),
                        detail));
    }

    // ---- values

    private List<DecisionPoint.Operand> operands(String condition, VariableContext view) {
        List<DecisionPoint.Operand> out = new ArrayList<>();
        for (String expr : ConditionEvaluator.operands(condition)) {
            Object value = ConditionEvaluator.resolvePath(expr, view);
            String root = expr.contains(".") ? expr.substring(0, expr.indexOf('.')) : expr;
            String text = value == null ? "" : String.valueOf(value);
            boolean hide = masked.contains(root);
            String shown =
                    hide
                            ? DecisionPoint.MASKED
                            : text.length() > DecisionPoint.DISPLAY_LIMIT
                                    ? text.substring(0, DecisionPoint.DISPLAY_LIMIT) + "…"
                                    : text;
            Bound b = bindings.get(root);
            out.add(
                    new DecisionPoint.Operand(
                            expr,
                            shown,
                            text.length(),
                            b == null ? null : b.step(),
                            b == null ? 0 : b.line(),
                            b == null ? null : b.by()));
        }
        return out;
    }

    /** The variables as a person looking at the run would see them: cut, masked and typed. */
    List<VariableView> variables(Map<String, Object> values) {
        List<VariableView> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String name = e.getKey();
            if (name.startsWith("_")) continue;
            Object value = e.getValue();
            boolean hidden = masked.contains(name);
            String full = value == null ? "" : String.valueOf(value);
            Bound b = bindings.get(name);
            out.add(
                    new VariableView(
                            name,
                            hidden ? DecisionPoint.MASKED : String.valueOf(shown(name, full)),
                            hidden ? 0 : full.length(),
                            typeOf(value),
                            b == null ? null : b.step(),
                            b == null ? 0 : b.line(),
                            b == null ? null : b.by()));
        }
        return out;
    }

    private static String typeOf(Object value) {
        if (value == null) return "empty";
        if (value instanceof Boolean) return "yes/no";
        if (value instanceof Number) return "number";
        if (value instanceof java.util.Collection<?>) return "list";
        if (value instanceof Map<?, ?>) return "object";
        return "text";
    }

    private Object shown(String name, Object value) {
        if (masked.contains(name)) return DecisionPoint.MASKED;
        if (value instanceof CharSequence s && s.length() > DecisionPoint.DISPLAY_LIMIT)
            return s.subSequence(0, DecisionPoint.DISPLAY_LIMIT) + "…";
        return value;
    }

    private void emit(DecisionPoint p) {
        DecisionListener l = listener;
        if (l != null) l.decided(p);
    }
}
