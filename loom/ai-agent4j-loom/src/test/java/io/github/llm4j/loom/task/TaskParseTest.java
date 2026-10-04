package io.github.llm4j.loom.task;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskParseTest {

    static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    static List<Statement> body(String statements) {
        return parse("workflow Main() {\n" + statements + "\n}").getWorkflows().get(0).getStatements();
    }

    static RunStmt run(String statement) {
        return (RunStmt) body(statement).get(0);
    }

    @Test
    void grammar() {
        RunStmt r = run("""
                run RefundPolicy(order = request.order_id, amount = request.amount) -> verdict
                    retry 2 backoff 500ms timeout 30s
                    on_failure { note "failed: {_error}" }
                """);
        assertEquals("RefundPolicy", r.getTaskName());
        assertEquals("verdict", r.getVariableName());
        assertEquals(2, r.getRetryCount());
        assertEquals(500, r.getBackoffMillis());
        assertEquals(30_000, r.getTimeoutMillis());
        assertEquals(1, r.getOnFailure().size());
        assertEquals(2, r.getArgs().size());
        assertEquals("order", r.getArgs().get(0).name());
        assertTrue(r.getLine() > 0);

        RunStmt bare = run("run Ping() -> pong");
        assertTrue(bare.getArgs().isEmpty());
        assertEquals(0, bare.getRetryCount());
        assertEquals(0, bare.getTimeoutMillis());
        assertTrue(bare.getOnFailure().isEmpty());

        assertEquals(1_000, run("run T() -> r backoff 1s").getBackoffMillis());
        assertEquals(120_000, run("run T() -> r timeout 2m").getTimeoutMillis());
        assertEquals(3, run("run T() -> r timeout 5s retry 3").getRetryCount(), "options in any order");
    }

    @Test
    void argumentForms() {
        RunStmt r = run("run T(a = request.amount, b = name, c = \"Refund for {request.order_id}\", d = 42, e = 3.5, f = true, g = false, h = -7) -> out");
        List<RunStmt.Arg> a = r.getArgs();
        assertEquals(RunStmt.Kind.REFERENCE, a.get(0).kind());
        assertEquals("request.amount", a.get(0).text());
        assertEquals(RunStmt.Kind.REFERENCE, a.get(1).kind());
        assertEquals(RunStmt.Kind.STRING, a.get(2).kind());
        assertEquals("Refund for {request.order_id}", a.get(2).text());
        assertEquals(RunStmt.Kind.NUMBER, a.get(3).kind());
        assertEquals("42", a.get(3).text());
        assertEquals(RunStmt.Kind.NUMBER, a.get(4).kind());
        assertEquals(RunStmt.Kind.BOOLEAN, a.get(5).kind());
        assertEquals("false", a.get(6).text());
        assertEquals(RunStmt.Kind.NUMBER, a.get(7).kind());
        assertEquals("-7", a.get(7).text());
    }

    @Test
    void keywordsAreValidArgumentAndTaskNames() {
        RunStmt r = run("run Lookup(type = kind, path = \"x\", to = who, model = m) -> out");
        assertEquals(List.of("type", "path", "to", "model"), r.getArgs().stream().map(RunStmt.Arg::name).toList());
        assertEquals("note", run("run note() -> out").getTaskName(), "a task may be named like a keyword");
    }

    @Test
    void dynamicResult() {
        RunStmt r = run("run T() -> {item.id}");
        assertEquals("{item.id}", r.getVariableName());
        assertTrue(r.hasDynamicVariable());
        assertFalse(run("run T() -> plain").hasDynamicVariable());
    }

    @Test
    void dynamicTaskNameRejected() {
        assertThrows(RuntimeException.class, () -> body("run {item.task}() -> out"));
        assertThrows(RuntimeException.class, () -> body("run \"T\"() -> out"));
    }

    @Test
    void budgetRejected() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> body("run T() -> out budget 100 tokens"));
        assertTrue(e.getMessage().contains("spends no tokens"), e.getMessage());
    }

    @Test
    void duplicateArgument() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> body("run T(a = 1, a = 2) -> out"));
        assertTrue(e.getMessage().contains("given twice"), e.getMessage());
    }

    @Test
    void helpfulErrors() {
        for (String[] bad : new String[][] {
                {"run T(a 1) -> out", "="},
                {"run T(a = ) -> out", "value"},
                {"run T(a = 1 -> out", "')'"},
                {"run T(a = 1) out", "->"},
                {"run T(a = 1) ->", "variable"},
                {"run T(1 = 2) -> out", "argument name"},
        }) {
            RuntimeException e = assertThrows(RuntimeException.class, () -> body(bad[0]), bad[0]);
            assertTrue(e.getMessage().contains(bad[1]), bad[0] + " -> " + e.getMessage());
        }
    }

    @Test
    void runStillAnIdentifier() {
        // as a variable name, in a payload, in a condition, and as the schedule key
        LoomScript script = parse("""
                agent A { model: "m" }
                workflow Main() {
                    delegate "go" to A -> run
                    alt (run == "x") { note "run {run}" } else { note "no" }
                    run T() -> out
                }
                workflow Sweep() { note "x" }
                schedule Nightly { cron: "0 7 * * *"  run: Sweep() }
                """);
        assertEquals(3, script.getWorkflows().get(0).getStatements().size());
        assertTrue(script.getWorkflows().get(0).getStatements().get(2) instanceof RunStmt);
        assertEquals(1, script.getSchedules().size());
        // `run` alone (no Name( after it) is not a statement start
        assertThrows(RuntimeException.class, () -> body("run"));
        assertThrows(RuntimeException.class, () -> body("run T -> out"));
    }

    @Test
    void noClassLoadingSyntax() {
        // a script can only name a task; there is no declaration that points at a class or file
        assertThrows(RuntimeException.class, () -> parse("task RefundPolicy { class: \"com.acme.Refund\" }"));
        assertThrows(RuntimeException.class, () -> parse("task RefundPolicy { use: \"com.acme.Refund\" }"));
    }

    @Test
    void nestsAnywhereAStatementDoes() {
        List<Statement> s = body("""
                alt (x == "y") { run A() -> a } else { run B() -> b }
                parallel { run C() -> c  run D() -> d }
                loop until (done == "yes") max 3 { run E() -> done }
                for each i in items { run F(x = i) -> {i.id} }
                """);
        assertEquals(4, s.size());
    }
}
