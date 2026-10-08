package io.github.llm4j.loom.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.cli.CliProbe;
import io.github.llm4j.loom.execution.ScriptValidator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskValidationTest {

    @TempDir
    Path dir;

    private static TaskRegistry registry() {
        return new TaskRegistry()
                .register(Task.pure("Pure", c -> TaskResult.ok()))
                .register(Task.reads("Reads", c -> TaskResult.ok()))
                .register(Task.changes("Pay", EffectPolicy.DEFAULT, c -> TaskResult.ok()))
                .register(Task.changes("PayIdem", new EffectPolicy(EffectPolicy.OnUnknown.SKIP, true, 0), c -> TaskResult.ok()))
                .register(Task.changes("PayRetry", new EffectPolicy(EffectPolicy.OnUnknown.RETRY, false, 0), c -> TaskResult.ok()));
    }

    private static List<ScriptValidator.Problem> check(String source, TaskRegistry tasks) {
        return new ScriptValidator().validate(TaskParseTest.parse(source),
                new ScriptValidator.Context().registeredTools(Set.of("Notify")).tasks(tasks));
    }

    private static List<String> errors(String source) {
        return check(source, registry()).stream().filter(p -> p.severity() == ScriptValidator.Severity.ERROR).map(ScriptValidator.Problem::toString).toList();
    }

    private static List<String> warnings(String source) {
        return check(source, registry()).stream().filter(p -> p.severity() == ScriptValidator.Severity.WARNING).map(ScriptValidator.Problem::toString).toList();
    }

    @Test
    void unknownTask() {
        assertThat(errors("workflow Main() {\n note \"x\"\n run Ghost() -> r\n}"))
                .anyMatch(e -> e.startsWith("line 3: run Ghost: unknown task") && e.contains("known tasks: Pay, PayIdem, PayRetry, Pure, Reads"));
        assertThat(check("workflow Main() { run Ghost() -> r }", new TaskRegistry()))
                .anyMatch(p -> p.message().contains("no tasks are registered") && p.severity() == ScriptValidator.Severity.ERROR);
        assertThat(errors("workflow Main() { run Pure() -> r  run Reads() -> s }")).isEmpty();
    }

    @Test
    void unknownTaskIsFoundWhereverAStatementCanBe() {
        for (String nested : new String[] {
                "alt (1 == 1) { run Ghost() -> r }",
                "alt (1 == 1) { note \"a\" } else { run Ghost() -> r }",
                "parallel { run Ghost() -> r }",
                "loop until (x == \"y\") max 2 { run Ghost() -> r }",
                "for each i in items { run Ghost() -> r }",
                "run Pure() -> p on_failure { run Ghost() -> r }",
                "guardrail (PII) { run Ghost() -> r }",
        }) {
            assertThat(errors("workflow Main(items) { " + nested + " }")).as(nested).anyMatch(e -> e.contains("run Ghost: unknown task"));
        }
    }

    @Test
    void retryUnsafe() {
        assertThat(errors("workflow Main() { run Pay(a = 1) -> r retry 2 }"))
                .anyMatch(e -> e.contains("run Pay") && e.contains("could repeat an effect") && e.contains("idempotent"));
        for (String ok : new String[] {
                "run Pure() -> r retry 3", "run Reads() -> r retry 3", "run PayIdem() -> r retry 3", "run PayRetry() -> r retry 3",
                "run Pay() -> r", "run Pay() -> r on_failure { note \"x\" }", "run Pay() -> r timeout 5s"}) {
            assertThat(errors("workflow Main() { " + ok + " }")).as(ok).isEmpty();
        }
    }

    @Test
    void rewindOverChangesTask() {
        String crossing = "workflow Main() { checkpoint a\nrun %s() -> r\nrewind to a at most 1 time %s }";
        assertThat(warnings(crossing.formatted("Pay", ""))).anyMatch(w -> w.contains("can change things outside the run") && w.contains("task Pay"));
        assertThat(warnings(crossing.formatted("Pay", "side effects: keep"))).noneMatch(w -> w.contains("can change things"));
        assertThat(errors(crossing.formatted("Pay", "side effects: repeat"))).anyMatch(e -> e.contains("not idempotent") && e.contains("Pay"));
        assertThat(errors(crossing.formatted("PayIdem", "side effects: repeat"))).isEmpty();
        assertThat(errors(crossing.formatted("PayRetry", "side effects: repeat"))).isEmpty();
        assertThat(warnings(crossing.formatted("Pure", ""))).noneMatch(w -> w.contains("can change things"));
        assertThat(warnings(crossing.formatted("Reads", ""))).noneMatch(w -> w.contains("can change things"));
    }

    @Test
    void resultVariableKnown() {
        String source = io.github.llm4j.loom.autonomy.Scripts.AGENT
                + "decision D { proposed by: Triager choices: a, b ask: x group cases by: tier remember: amount, review.score }\n";
        assertThat(errors(source + "workflow W(tier, amount) { run Pure() -> review\n decide D -> v }")).isEmpty();
        assertThat(errors(source + "workflow W(tier, amount) { decide D -> v }")).anyMatch(e -> e.contains("review.score"));
        assertThat(errors(source + "workflow W(tier, amount) { run Pure() -> review on_failure { note \"x\" }\n decide D -> v }")).isEmpty();
    }

    @Test
    void walkerSeesOnFailure() {
        var script = TaskParseTest.parse("agent A { model: \"m\" }\nworkflow Main() { run Pure() -> r on_failure { delegate \"x\" to A -> y } }");
        assertThat(StatementWalker.any(script, s -> s instanceof DelegateStmt)).isTrue();
    }

    // ---- the `weave check` CLI path, with a task found through the ServiceLoader ----------------------------------

    private int weaveCheck(String source, ByteArrayOutputStream out) throws Exception {
        Path f = dir.resolve("s.loom");
        Files.writeString(f, source);
        return CliProbe.check(f.toFile(), false, new PrintStream(out, true), Map.<String, String>of()::get, new AtomicInteger());
    }

    @Test
    void weaveCheckFindsTasksOnTheClassPathAndNamesTheUnknownOnes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(weaveCheck("workflow Main() { run ServiceTask(who = \"x\") -> r }", out)).isZero();
        assertThat(out.toString()).contains("✓ s.loom: ready to run");

        out.reset();
        assertThat(weaveCheck("workflow Main() {\n run Ghost() -> r\n}", out)).isEqualTo(2);
        assertThat(out.toString()).contains("✗ line 2: run Ghost: unknown task").contains("known tasks:").contains("ServiceTask").contains("1 problem in s.loom");
    }

    @Test
    void weaveRunExecutesAClassPathTaskWithNoModel() throws Exception {
        Path f = dir.resolve("run.loom");
        Files.writeString(f, "workflow Main() { run ServiceTask(who = \"cli\") -> r }");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        AtomicInteger models = new AtomicInteger();
        int code = CliProbe.run(f.toFile(), "text", new PrintStream(out, true), new PrintStream(err, true), Map.<String, String>of()::get,
                model -> { models.incrementAndGet(); throw new IllegalStateException("no model should be created"); });
        assertThat(code).as(out + "\n" + err).isZero();
        assertThat(models.get()).isZero();
        assertThat(err.toString()).contains("⚙ run ServiceTask").contains("✔ ServiceTask → ok");
    }
}
