package io.github.llm4j.nirmaan.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.eval.testing.ScriptedClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.prompt.PromptSettings;
import io.github.llm4j.loom.prompt.PromptSupport;
import io.github.llm4j.loom.runtime.HumanInterface;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Runs the real workflow with the real tasks (real {@code python3} builds in a temporary workspace) and a scripted model, and keeps
 * everything a trajectory test wants to look at: the trace, the questions a person was asked, and how the run ended.
 *
 * <p>The tiny project the script's agents "write" is an {@code add(a, b)} function in Python's standard {@code unittest}, so no package
 * has to be downloaded and the whole factory runs in about a second.
 */
final class Factory {

    static final Path SCRIPT = Path.of("src", "main", "resources", "main.loom").toAbsolutePath();
    static final ObjectMapper JSON = new ObjectMapper();

    // ---- what the scripted agents write -------------------------------------------------------------------------------

    static final String TEST_FILE = "test_add.py";
    static final String TEST_ADD = "import unittest\nfrom add import add\n\nclass AddTest(unittest.TestCase):\n    def test_adds(self):\n        self.assertEqual(add(2, 3), 5)\n\nif __name__ == '__main__':\n    unittest.main()\n";
    static final String TEST_ADD_WRONG = TEST_ADD.replace("5)", "6)");
    static final String ADD_STUB = "def add(a, b):\n    raise NotImplementedError()\n";
    static final String ADD_RIGHT = "import sys\n\ndef add(a, b):\n    return a + b\n\nif __name__ == '__main__':\n    print(add(int(sys.argv[1]), int(sys.argv[2])))\n";
    static final String ADD_WRONG = ADD_RIGHT.replace("a + b", "a - b");
    static final String E2E = "import subprocess, sys\nout = subprocess.run([sys.executable, 'add.py', '2', '3'], capture_output=True, text=True)\nsys.exit(0 if out.stdout.strip() == '5' else 1)\n";

    static Map<String, Object> spec(String buildCommand, String e2eCommand) {
        Map<String, Object> spec = new java.util.LinkedHashMap<>();
        spec.put("stack", "Python 3 with unittest");
        spec.put("rationale", "no dependencies to download");
        spec.put("requirements", List.of("add(a, b) returns the sum", "add.py prints the sum of its two arguments"));
        spec.put("unit_test_strategy", "unittest");
        spec.put("e2e_strategy", "run add.py and compare its output");
        spec.put("build_command", buildCommand);
        spec.put("test_command", "python3 -m unittest");
        spec.put("run_command", "python3 add.py 2 3");
        spec.put("e2e_command", e2eCommand);
        spec.put("structure", List.of("add.py", "test_add.py", "e2e-tests/run_e2e.py"));
        spec.put("plan", List.of("write the test", "write add"));
        spec.put("change_summary", "initial");
        return spec;
    }

    static Map<String, Object> file(String path, String content) {
        return Map.of("path", path, "content", content);
    }

    /** What Vihaan answers: a summary and complete files. */
    @SafeVarargs
    static String vihaan(Map<String, Object>... files) {
        return answer(Map.of("summary", "s", "files", List.of(files)));
    }

    @SafeVarargs
    static String drishti(Map<String, Object>... files) {
        return answer(Map.of("notes", "n", "files", List.of(files)));
    }

    static String verdict(String verdict) {
        return answer(Map.of("verdict", verdict, "detail", "looked at it: " + verdict));
    }

    /** A model's final answer, as the agent protocol wants it. */
    static String answer(Object value) {
        try {
            return ScriptedClient.reactFinal(value instanceof String s ? s : JSON.writeValueAsString(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The scripted model every test starts from: a smooth run in which every agent does its job at the first try. A test adds the rules
     * that make a run interesting with {@link Script#when}; they are consulted before the smooth defaults, because the first match wins.
     */
    static Script smooth(String e2eCommand) {
        return new Script(e2eCommand);
    }

    static final class Script {
        private final String e2eCommand;
        private String buildCommand = "None";
        private final List<Runnable> overrides = new ArrayList<>();
        private final ScriptedClient client = new ScriptedClient();

        Script(String e2eCommand) { this.e2eCommand = e2eCommand; }

        /** The build command Rishi's spec names (default: none, which passes at once). */
        Script withBuildCommand(String command) {
            this.buildCommand = command;
            return this;
        }

        /** The agent whose task contains {@code needle} answers {@code answer}. */
        Script when(String needle, String answer) {
            overrides.add(() -> client.whenTaskSeen(needle, answer));
            return this;
        }

        /** The first such task gets the first answer, the next one the second, and so on (the last repeats). */
        Script whenThen(String needle, String first, String... then) {
            overrides.add(() -> client.whenThen(text -> ScriptedClient.currentTask(text).contains(needle), first, then));
            return this;
        }

        ScriptedClient build() {
            overrides.forEach(Runnable::run);
            return client
                    .whenTaskSeen("Write the PRD", answer("# PRD\nAdd two numbers.\n## Acceptance Criteria\n- add(2, 3) is 5"))
                    .whenTaskSeen("PRD:\\n", answer(spec(buildCommand, e2eCommand)))
                    .whenTaskSeen("MODE: RED", vihaan(file(TEST_FILE, TEST_ADD), file("add.py", ADD_STUB)))
                    .whenTaskSeen("MODE: WRITE", drishti(file("e2e-tests/run_e2e.py", E2E)))
                    .whenTaskSeen("MODE: GREEN", vihaan(file("add.py", ADD_RIGHT)))
                    .whenTaskSeen("STAGE:", verdict("APPROVED"))
                    .otherwise(answer("ok"));
        }
    }

    // ---- the run ------------------------------------------------------------------------------------------------------

    /** The person at the other end of the screen: records every question and answers it with {@code answer}. */
    static final class Person implements HumanInterface {
        final List<String> questions = Collections.synchronizedList(new ArrayList<>());
        final List<HumanInterface.Hints.Kind> kinds = Collections.synchronizedList(new ArrayList<>());
        private final Function<String, String> answer;

        Person(Function<String, String> answer) { this.answer = answer; }

        @Override public String promptHuman(String message) {
            questions.add(message);
            return answer.apply(message);
        }

        @Override public String promptHuman(String stepId, String message, Hints hints) {
            kinds.add(hints.kind());
            return promptHuman(message);
        }
    }


    /** How a run ended and everything seen on the way. */
    record Run(Path workspace, List<TraceEvent> events, Person person, Throwable error, HarnessExecutor executor) {

        Path code() { return workspace.resolve("t1").resolve("code"); }

        /** Names of the tasks that ran to the end, in order ("RunCommand" once per run of it). */
        List<String> tasks() {
            return events.stream().filter(e -> e.type().equals(TraceEvent.TASK_END)).map(e -> e.text().split(" ")[0]).toList();
        }

        long count(String type) { return events.stream().filter(e -> e.type().equals(type)).count(); }

        long tasksNamed(String name) { return tasks().stream().filter(name::equals).count(); }
    }

    static Run run(Path workspace, ScriptedClient model, Person person) throws Exception {
        return run(workspace, model, person, null);
    }

    static Run run(Path workspace, ScriptedClient model, Person person, Long tokenLimit) throws Exception {
        System.setProperty("nirmaan.workspace", workspace.toString());
        LoomScript script = new LoomLoader().load(SCRIPT.toString());
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), name -> model);
        executor.setHumanInterface(person);
        executor.setBaseDir(SCRIPT.getParent());
        executor.setEnvLookup(name -> "test");
        executor.setPriceTable(io.github.llm4j.budget.PriceTable.load(Path.of("prices.properties").toAbsolutePath()));   // the script has a cost budget
        executor.setPromptCatalog(PromptSupport.catalog(script, SCRIPT, PromptSettings.NONE));
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        executor.addTraceListener(events::add);
        if (tokenLimit != null) executor.setBudgetOverrides(tokenLimit, null, (BigDecimal) null);
        executor.initialize();
        Throwable error = null;
        try {
            executor.executeWorkflow("Main", Map.of("idea_text", "a function that adds two numbers", "project_id", "t1"));
        } catch (Throwable t) {
            error = t;
        } finally {
            executor.shutdown();
        }
        return new Run(workspace, events, person, error, executor);
    }
}
