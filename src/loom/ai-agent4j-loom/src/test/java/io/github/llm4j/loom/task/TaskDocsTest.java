package io.github.llm4j.loom.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.execution.ScriptValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The documentation of tasks stays true: its Loom examples parse and pass the checks, its links resolve, and it says what the design says. */
class TaskDocsTest {

    static final Path ROOT = Path.of("../../..").normalize();
    static final Path LOOM = ROOT.resolve("src/loom/ai-agent4j-loom");

    /** The documents that show Loom examples with {@code run}. */
    static final List<Path> EXAMPLE_DOCS = List.of(
            LOOM.resolve("LOOM_GUIDE.md"),
            ROOT.resolve("docs/guide/06-build-the-workflow.md"),
            ROOT.resolve("docs/security/securing-workflows.md"));

    private static String read(Path p) throws Exception {
        return Files.readString(p);
    }

    private static List<String> loomBlocks(String markdown) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("```loom\\n(.*?)```", Pattern.DOTALL).matcher(markdown);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static List<String> taskBlocks(Path doc) throws Exception {
        return loomBlocks(read(doc)).stream().filter(b -> Pattern.compile("(?m)^\\s*run \\w+\\(").matcher(b).find()).toList();
    }

    @Test
    void documentedExamplesParse() throws Exception {
        int seen = 0;
        for (Path doc : EXAMPLE_DOCS) {
            for (String block : taskBlocks(doc)) {
                seen++;
                LoomScript script;
                try {
                    script = TaskParseTest.parse(block);
                } catch (RuntimeException e) {
                    throw new AssertionError(doc + " has a task example that does not parse:\n" + block + "\n→ " + e.getMessage(), e);
                }
                assertThat(StatementWalker.any(script, s -> s instanceof RunStmt)).as(doc.toString()).isTrue();
            }
        }
        assertThat(seen).as("the guide, chapter 6 and the security guide each show at least one task example").isGreaterThanOrEqualTo(3);
    }

    @Test
    void documentedExamplesPassTheChecksWithTheTasksTheyName() throws Exception {
        TaskRegistry tasks = TaskRegistry.discovered(); // RefundPolicy and IssueRefund come from this module's test services
        assertThat(tasks.names()).contains("RefundPolicy", "IssueRefund");
        for (Path doc : EXAMPLE_DOCS) {
            for (String block : taskBlocks(doc)) {
                var problems = new ScriptValidator().validate(TaskParseTest.parse(block),
                        new ScriptValidator.Context().tasks(tasks).registeredTools(Set.of()).humanInterface(true));
                // the snippets are fragments: the agents they delegate to are defined elsewhere in the page
                assertThat(problems).as(doc + ":\n" + block).noneMatch(p -> p.severity() == ScriptValidator.Severity.ERROR && !p.message().contains("there is no agent named"));
            }
        }
    }

    @Test
    void theRefundFixtureAndTheCtkScriptsParseAndPass() throws Exception {
        TaskRegistry tasks = TaskRegistry.discovered();
        for (Path file : List.of(Path.of("src/test/resources/task/refund.loom"))) {
            var problems = new ScriptValidator().validate(TaskParseTest.parse(read(file)),
                    new ScriptValidator.Context().tasks(tasks).humanInterface(true));
            assertThat(problems).as(file.toString()).noneMatch(p -> p.severity() == ScriptValidator.Severity.ERROR);
        }
        for (String name : List.of("task_basic", "task_rejected")) {
            LoomScript script = TaskParseTest.parse(read(ROOT.resolve("src/loom/ctk/scripts/" + name + ".loom")));
            assertThat(StatementWalker.any(script, s -> s instanceof RunStmt)).as(name).isTrue();
        }
    }

    @Test
    void theGuidesRefundExampleIsTheTestedOne() throws Exception {
        // the example the guide shows is the fixture the end-to-end tests run, so the docs cannot drift from tested behaviour
        String guide = read(LOOM.resolve("LOOM_GUIDE.md"));
        String fixture = read(Path.of("src/test/resources/task/refund.loom"));
        String normalized = guide.replaceAll("\\s+", " ");
        for (String line : fixture.lines().filter(l -> l.trim().startsWith("run ") || l.trim().startsWith("alt (")).toList()) {
            assertThat(normalized).as("the guide's refund example lacks: " + line.trim()).contains(line.trim().replaceAll("\\s+", " "));
        }
    }

    @Test
    void docsMentionTasks() throws Exception {
        String guide = read(LOOM.resolve("LOOM_GUIDE.md"));
        assertThat(guide).contains("### Tasks: deterministic steps (`run`)", "TaskContext", "TaskResult", "TaskEffect", "EffectPolicy",
                "idempotencyKey", "TaskNotPerformed", "META-INF/services/io.github.llm4j.agent.task.Task", "setTaskRegistry",
                "tasks-deterministic-steps-run", "side effects", "task_replayed");
        assertThat(read(LOOM.resolve("README.md"))).contains("| `run` |").contains("tasks-deterministic-steps-run");
        assertThat(read(LOOM.resolve("WHY_LOOM.md"))).contains("Code where it must be code").contains("run RefundPolicy");
        assertThat(read(LOOM.resolve("LOOM_PROMPT.md"))).contains("Deterministic Task (no model)").contains("run <TaskName>(");
        assertThat(read(ROOT.resolve("llms.txt"))).contains("Creating-Tasks.md").contains("`run Task(arg = value) -> result`");
        assertThat(read(ROOT.resolve("src/ai-agent4j/llms.txt"))).contains("`Task` (`io.github.llm4j.agent.task`)");
        assertThat(read(ROOT.resolve("docs/guide/01-decide-your-agents.md"))).contains("## Agent or task?");
        assertThat(read(ROOT.resolve("docs/guide/06-build-the-workflow.md"))).contains("## Tasks: the steps with no model");
        assertThat(read(ROOT.resolve("docs/guide/07-validate-and-audit.md"))).contains("not registered").contains("not idempotent");
        assertThat(read(ROOT.resolve("docs/guide/08-trajectory-tests.md"))).contains("runsTasksInOrder", "runsTaskTimes", "taskEndedWith");
        assertThat(read(ROOT.resolve("docs/guide/10-best-practices.md"))).contains("Put money, policy and side effects in tasks");
        assertThat(read(ROOT.resolve("docs/security/securing-workflows.md"))).contains("Or take the model out of it: tasks");
        assertThat(read(ROOT.resolve("docs/articles/LOOM_ARTICLE.md"))).contains("| `run` |").contains("thirteen");
        assertThat(read(ROOT.resolve("src/loom/ctk/README.md"))).contains("Task steps (`run`)").contains("sorted-key");
        assertThat(read(ROOT.resolve("src/ai-agent4j/wiki/Creating-Tasks.md"))).contains("TaskRegistry.discovered()", "TaskNotPerformed", "idempotencyKey()");
        assertThat(read(ROOT.resolve("src/loom/vscode-loom/syntaxes/loom.tmLanguage.json"))).containsPattern("\\|run\\|");
    }

    @Test
    void everyLocalLinkInTheNewAndChangedDocsResolves() throws Exception {
        List<Path> docs = new ArrayList<>(EXAMPLE_DOCS);
        docs.addAll(List.of(
                LOOM.resolve("README.md"), LOOM.resolve("WHY_LOOM.md"),
                ROOT.resolve("src/ai-agent4j/wiki/Creating-Tasks.md"), ROOT.resolve("src/ai-agent4j/wiki/Creating-Custom-Tools.md"),
                ROOT.resolve("src/ai-agent4j/wiki/Home.md"),
                ROOT.resolve("docs/guide/01-decide-your-agents.md"), ROOT.resolve("docs/guide/07-validate-and-audit.md"),
                ROOT.resolve("docs/guide/08-trajectory-tests.md"), ROOT.resolve("docs/guide/10-best-practices.md"),
                ROOT.resolve("docs/guide/README.md"), ROOT.resolve("src/loom/ctk/README.md"),
                ROOT.resolve(".kiro/specs/loom-tasks/design.md"), ROOT.resolve(".kiro/specs/loom-tasks/test-strategy.md"),
                ROOT.resolve(".kiro/specs/loom-tasks/verification.md")));
        int seen = 0;
        for (Path doc : docs) {
            Matcher m = Pattern.compile("\\]\\(([^)#:\\s]+)(#[^)]*)?\\)").matcher(read(doc));
            while (m.find()) {
                seen++;
                assertThat(doc.getParent().resolve(m.group(1))).as(doc + " links " + m.group(0)).exists();
            }
        }
        assertThat(seen).isGreaterThan(20);
    }

    @Test
    void theGuideAnchorsTheDocsLinkToExist() throws Exception {
        String guide = read(LOOM.resolve("LOOM_GUIDE.md"));
        // GitHub anchor of "### Tasks: deterministic steps (`run`)"
        assertThat(guide).contains("### Tasks: deterministic steps (`run`)");
        assertThat("tasks-deterministic-steps-run").isEqualTo(
                "Tasks: deterministic steps (`run`)".toLowerCase().replaceAll("[^a-z0-9 -]", "").trim().replace(' ', '-'));
    }
}
